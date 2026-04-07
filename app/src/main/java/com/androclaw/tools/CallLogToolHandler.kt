package com.androclaw.tools

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallLogToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val permError = permissionHelper.ensurePermissionsForTool(context, "call_log")
        if (permError != null) return permError

        val contactName = input["contact_name"] as? String
        val type = (input["type"] as? String)?.lowercase() ?: "all"
        val count = (input["count"] as? Number)?.toInt() ?: 15
        val days = (input["days"] as? Number)?.toInt()

        return try {
            val entries = queryCallLog(contactName, type, count, days)
            if (entries.isEmpty()) {
                "No call log entries found" +
                    (if (contactName != null) " for \"$contactName\"" else "") +
                    (if (type != "all") " of type \"$type\"" else "") + "."
            } else {
                "Call log (${entries.size} entries):\n" + entries.joinToString("\n") { it }
            }
        } catch (e: Exception) {
            "Failed to read call log: ${e.message}"
        }
    }

    private fun queryCallLog(
        contactName: String?,
        type: String,
        count: Int,
        days: Int?
    ): List<String> {
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // Filter by call type
        when (type) {
            "missed" -> {
                selectionParts.add("${CallLog.Calls.TYPE} = ?")
                selectionArgs.add(CallLog.Calls.MISSED_TYPE.toString())
            }
            "incoming", "received" -> {
                selectionParts.add("${CallLog.Calls.TYPE} = ?")
                selectionArgs.add(CallLog.Calls.INCOMING_TYPE.toString())
            }
            "outgoing", "dialed" -> {
                selectionParts.add("${CallLog.Calls.TYPE} = ?")
                selectionArgs.add(CallLog.Calls.OUTGOING_TYPE.toString())
            }
            "rejected" -> {
                selectionParts.add("${CallLog.Calls.TYPE} = ?")
                selectionArgs.add(CallLog.Calls.REJECTED_TYPE.toString())
            }
        }

        // Filter by contact name
        val resolvedNumber = if (contactName != null) resolveContactNumber(contactName) else null
        if (resolvedNumber != null) {
            selectionParts.add("${CallLog.Calls.NUMBER} LIKE ?")
            selectionArgs.add("%${resolvedNumber.takeLast(10)}%")
        } else if (contactName != null) {
            selectionParts.add("${CallLog.Calls.CACHED_NAME} LIKE ?")
            selectionArgs.add("%$contactName%")
        }

        // Filter by days
        if (days != null) {
            val cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000)
            selectionParts.add("${CallLog.Calls.DATE} > ?")
            selectionArgs.add(cutoff.toString())
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val results = mutableListOf<String>()
        var cursor: Cursor? = null

        try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                selection,
                args,
                "${CallLog.Calls.DATE} DESC"
            )

            val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

            if (cursor != null) {
                while (cursor.moveToNext() && results.size < count) {
                    val number = cursor.getString(0) ?: "Unknown"
                    val name = cursor.getString(1) ?: getContactName(number) ?: number
                    val callType = cursor.getInt(2)
                    val date = cursor.getLong(3)
                    val duration = cursor.getLong(4)

                    val typeStr = when (callType) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                        else -> "Unknown"
                    }

                    val durationStr = formatDuration(duration)
                    val timeStr = dateFormat.format(Date(date))
                    val ago = timeAgo(date)

                    results.add("$name — $typeStr — $durationStr — $timeStr ($ago)")
                }
            }
        } finally {
            cursor?.close()
        }

        return results
    }

    private fun getContactName(phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun resolveContactNumber(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, arrayOf("%$name%"), null)
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    private fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ago"
            hours > 0 -> "${hours}h ago"
            minutes > 0 -> "${minutes}m ago"
            else -> "just now"
        }
    }
}

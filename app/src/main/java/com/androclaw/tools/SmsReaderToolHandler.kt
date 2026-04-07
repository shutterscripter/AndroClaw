package com.androclaw.tools

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsReaderToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val permError = permissionHelper.ensurePermissionsForTool(context, "read_sms")
        if (permError != null) return permError

        val contactName = input["contact_name"] as? String
        val phoneNumber = input["phone_number"] as? String
        val searchQuery = input["query"] as? String ?: input["search_query"] as? String
        val count = (input["count"] as? Number)?.toInt() ?: 10
        val folder = (input["folder"] as? String)?.lowercase() ?: "all"

        return try {
            val messages = readMessages(contactName, phoneNumber, searchQuery, count, folder)
            if (messages.isEmpty()) {
                "No messages found" + when {
                    contactName != null -> " from \"$contactName\""
                    phoneNumber != null -> " from $phoneNumber"
                    searchQuery != null -> " matching \"$searchQuery\""
                    else -> ""
                } + "."
            } else {
                "Messages (${messages.size}):\n" + messages.joinToString("\n\n") { it }
            }
        } catch (e: Exception) {
            "Failed to read messages: ${e.message}"
        }
    }

    private fun readMessages(
        contactName: String?,
        phoneNumber: String?,
        searchQuery: String?,
        count: Int,
        folder: String
    ): List<String> {
        val uri = when (folder) {
            "inbox" -> Uri.parse("content://sms/inbox")
            "sent" -> Uri.parse("content://sms/sent")
            else -> Uri.parse("content://sms")
        }

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // Resolve contact name to number
        val resolvedNumber = if (contactName != null) {
            resolveContactNumber(contactName)
        } else phoneNumber

        if (resolvedNumber != null) {
            selectionParts.add("address LIKE ?")
            selectionArgs.add("%${resolvedNumber.takeLast(10)}%")
        }

        if (searchQuery != null) {
            selectionParts.add("body LIKE ?")
            selectionArgs.add("%$searchQuery%")
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val results = mutableListOf<String>()
        var cursor: Cursor? = null

        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "body", "date", "type"),
                selection,
                args,
                "date DESC"
            )

            if (cursor != null) {
                val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                while (cursor.moveToNext() && results.size < count) {
                    val address = cursor.getString(0) ?: "Unknown"
                    val body = cursor.getString(1) ?: ""
                    val date = cursor.getLong(2)
                    val type = cursor.getInt(3)

                    val direction = when (type) {
                        1 -> "Received"
                        2 -> "Sent"
                        else -> "Message"
                    }

                    val contactLabel = getContactName(address) ?: address
                    val timeStr = dateFormat.format(Date(date))
                    val ago = timeAgo(date)

                    results.add("$direction — $contactLabel ($timeStr, $ago)\n$body")
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

    private fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
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

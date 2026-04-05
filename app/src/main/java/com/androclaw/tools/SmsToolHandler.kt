package com.androclaw.tools

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        // Request SMS permission if not granted
        val permError = permissionHelper.ensurePermissionsForTool(context, "send_sms")
        if (permError != null) return permError

        val message = input["message"] as? String ?: return "Missing message text"
        var phoneNumber = input["phone_number"] as? String
        val contactName = input["contact_name"] as? String

        if (phoneNumber == null && contactName != null) {
            // Need contacts permission to resolve name
            val contactPermError = permissionHelper.ensurePermissionsForTool(context, "get_contacts")
            if (contactPermError != null) return "Need contacts permission to look up \"$contactName\". $contactPermError"

            phoneNumber = resolveContactNumber(contactName)
                ?: return "Could not find contact: $contactName"
        }

        if (phoneNumber == null) {
            return "No phone number or contact name provided"
        }

        return try {
            val smsManager = SmsManager.getDefault()
            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            "SMS sent successfully to ${contactName ?: phoneNumber}: \"$message\""
        } catch (e: Exception) {
            "Failed to send SMS: ${e.message}"
        }
    }

    private fun resolveContactNumber(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        } finally {
            cursor?.close()
        }
        return null
    }
}

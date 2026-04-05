package com.androclaw.tools

import android.content.Context
import android.database.Cursor
import android.net.Uri
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

    fun execute(input: Map<String, Any>): String {
        if (!permissionHelper.hasSmsPermission(context)) {
            return "SMS permission not granted. Please grant SMS permission in app settings."
        }

        val message = input["message"] as? String ?: return "Missing message text"
        var phoneNumber = input["phone_number"] as? String
        val contactName = input["contact_name"] as? String

        // Resolve contact name to phone number if needed
        if (phoneNumber == null && contactName != null) {
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
        if (!permissionHelper.hasContactsPermission(context)) return null

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

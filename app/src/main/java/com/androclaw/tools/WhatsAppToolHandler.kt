package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhatsAppToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val contactName = input["contact_name"] as? String ?: return "Missing contact_name"
        val message = input["message"] as? String ?: return "Missing message"

        // Request contacts permission to resolve name, but don't fail if denied
        permissionHelper.ensurePermissionsForTool(context, "get_contacts")
        val phoneNumber = if (permissionHelper.hasContactsPermission(context)) {
            resolvePhoneNumber(contactName)
        } else null

        return try {
            if (phoneNumber != null) {
                // Use direct WhatsApp API with phone number
                val cleanNumber = phoneNumber.replace("[^0-9+]".toRegex(), "")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    `package` = "com.whatsapp"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened WhatsApp chat with $contactName ($phoneNumber). Message pre-filled: \"$message\""
            } else {
                // Fallback: open WhatsApp send intent
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    `package` = "com.whatsapp"
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(sendIntent)
                "Opened WhatsApp with message. Could not resolve contact \"$contactName\" — please select the recipient manually."
            }
        } catch (e: Exception) {
            if (e.message?.contains("No Activity found") == true) {
                "WhatsApp is not installed on this device."
            } else {
                "Failed to send WhatsApp message: ${e.message}"
            }
        }
    }

    private fun resolvePhoneNumber(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }
}

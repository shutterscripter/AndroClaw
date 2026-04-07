package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.androclaw.service.AndroClawAccessibilityService
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhatsAppToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val contactName = input["contact_name"] as? String
        val groupName = input["group_name"] as? String
        val message = input["message"] as? String
        val filePath = input["file_path"] as? String
        val fileName = input["file_name"] as? String

        if (contactName == null && groupName == null) {
            return "Missing contact_name or group_name — provide one to specify the recipient."
        }

        // Group chat path
        if (groupName != null) {
            return handleGroupMessage(groupName, message, filePath, fileName)
        }

        // Individual contact path
        try {
            permissionHelper.ensurePermissionsForTool(context, "get_contacts")
        } catch (_: Exception) {}
        val phoneNumber = if (permissionHelper.hasContactsPermission(context)) {
            resolvePhoneNumber(contactName!!)
        } else null

        // If a file is specified, send file via WhatsApp
        if (filePath != null || fileName != null) {
            return sendFileViaWhatsApp(contactName!!, phoneNumber, filePath, fileName, message)
        }

        // Text-only message
        if (message == null) return "Missing message — provide 'message' for text or 'file_path'/'file_name' to send a file."

        return try {
            if (phoneNumber != null) {
                val cleanNumber = phoneNumber.replace("[^0-9+]".toRegex(), "")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    `package` = "com.whatsapp"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened WhatsApp chat with $contactName ($phoneNumber). Message pre-filled: \"$message\""
            } else {
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

    /**
     * Handle sending to a WhatsApp group.
     * WhatsApp doesn't expose group JIDs via public APIs, so we use accessibility
     * to navigate WhatsApp's UI: open WhatsApp → search for group → open it → send.
     */
    private suspend fun handleGroupMessage(
        groupName: String,
        message: String?,
        filePath: String?,
        fileName: String?
    ): String {
        val service = AndroClawAccessibilityService.instance
            ?: return handleGroupFallback(groupName, message, filePath, fileName)

        try {
            // Step 1: Open WhatsApp
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: return "WhatsApp is not installed on this device."
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)
            delay(1500)

            // Step 2: Tap the search icon to open search
            service.tapById("com.whatsapp:id/menuitem_search")
            delay(300)
            service.tapByText("Search")
            delay(800)

            // Step 3: Type the group name in the search field
            service.typeText(groupName)
            delay(1200)

            // Step 4: Tap the group from search results
            val groupResult = service.tapByText(groupName)
            if (groupResult.contains("not found", ignoreCase = true)) {
                // Group not found in search results
                return handleGroupFallback(groupName, message, filePath, fileName)
            }
            delay(1000)

            // Step 5: Send file or message
            if (filePath != null || fileName != null) {
                return sendFileInOpenChat(groupName, filePath, fileName, message)
            }

            if (message != null) {
                // Type and send the message
                service.tapById("com.whatsapp:id/entry")
                delay(200)
                service.tapByText("Type a message")
                delay(300)
                service.typeText(message)
                delay(300)
                service.tapById("com.whatsapp:id/send")
                return "Sent message to group \"$groupName\": \"$message\""
            }

            return "Opened WhatsApp group \"$groupName\"."
        } catch (e: Exception) {
            return handleGroupFallback(groupName, message, filePath, fileName)
        }
    }

    /**
     * Fallback when accessibility is not available: share via ACTION_SEND
     * and let the user pick the group manually.
     */
    private fun handleGroupFallback(
        groupName: String,
        message: String?,
        filePath: String?,
        fileName: String?
    ): String {
        return try {
            if (filePath != null || fileName != null) {
                val contentUri: Uri
                val displayName: String
                val mimeType: String

                if (filePath != null) {
                    val file = File(filePath)
                    if (!file.exists()) return "File not found: $filePath"
                    contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    displayName = file.name
                    mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                } else {
                    val found = findFileUri(fileName!!) ?: return "File \"$fileName\" not found on device."
                    contentUri = found.first
                    displayName = found.second
                    mimeType = found.third
                }

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    `package` = "com.whatsapp"
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    if (message != null) putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(sendIntent)
                "Opened WhatsApp with $displayName attached. Please select the group \"$groupName\" and tap send."
            } else if (message != null) {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    `package` = "com.whatsapp"
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(sendIntent)
                "Opened WhatsApp with message. Please select the group \"$groupName\" and tap send."
            } else {
                "Please provide a message or file to send to group \"$groupName\"."
            }
        } catch (e: Exception) {
            if (e.message?.contains("No Activity found") == true) {
                "WhatsApp is not installed on this device."
            } else {
                "Failed to share to WhatsApp group: ${e.message}"
            }
        }
    }

    /**
     * Send a file in an already-open WhatsApp chat using accessibility.
     */
    private suspend fun sendFileInOpenChat(
        recipientName: String,
        filePath: String?,
        fileName: String?,
        caption: String?
    ): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not available for file sharing in group."

        // Use the share intent to send file to the already-open chat
        val contentUri: Uri
        val displayName: String
        val mimeType: String

        if (filePath != null) {
            val file = File(filePath)
            if (!file.exists()) return "File not found: $filePath"
            contentUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                return "Cannot share this file: ${e.message}"
            }
            displayName = file.name
            mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        } else if (fileName != null) {
            val found = findFileUri(fileName) ?: return "File \"$fileName\" not found on device."
            contentUri = found.first
            displayName = found.second
            mimeType = found.third
        } else {
            return "No file specified."
        }

        // Tap the attachment/clip icon
        service.tapById("com.whatsapp:id/camera_btn")
        delay(300)
        service.tapById("com.whatsapp:id/attach_btn")
        delay(300)
        service.tapByText("Attach")
        delay(800)

        // Tap "Document" option to send any file
        service.tapByText("Document")
        delay(1000)

        // Fallback: use share intent to push file into the open chat
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            `package` = "com.whatsapp"
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            if (caption != null) putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(sendIntent)
        delay(500)

        // Try to tap the group name from the share target list
        service.tapByText(recipientName)
        delay(500)

        return "Sharing $displayName to group \"$recipientName\" via WhatsApp. Please confirm and tap send.${caption?.let { " Caption: \"$it\"" } ?: ""}"
    }

    private fun sendFileViaWhatsApp(
        contactName: String,
        phoneNumber: String?,
        filePath: String?,
        fileName: String?,
        caption: String?
    ): String {
        // Resolve the file URI
        val contentUri: Uri
        val displayName: String
        val mimeType: String

        if (filePath != null) {
            val file = File(filePath)
            if (!file.exists()) return "File not found: $filePath"
            contentUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                return "Cannot share this file: ${e.message}"
            }
            displayName = file.name
            mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        } else if (fileName != null) {
            val found = findFileUri(fileName) ?: return "File \"$fileName\" not found on device."
            contentUri = found.first
            displayName = found.second
            mimeType = found.third
        } else {
            return "Provide file_path or file_name to send a file."
        }

        return try {
            // WhatsApp's jid format needs number without + prefix
            val cleanNumber = phoneNumber?.replace("[^0-9]".toRegex(), "")

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                `package` = "com.whatsapp"
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                if (caption != null) putExtra(Intent.EXTRA_TEXT, caption)
                // Target specific contact if we have the number
                if (cleanNumber != null) {
                    putExtra("jid", "$cleanNumber@s.whatsapp.net")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(sendIntent)

            if (phoneNumber != null) {
                "Sending $displayName to $contactName ($phoneNumber) via WhatsApp. Please tap send to confirm.${caption?.let { " Caption: \"$it\"" } ?: ""}"
            } else {
                "Opened WhatsApp with $displayName attached. Please select $contactName and tap send."
            }
        } catch (e: Exception) {
            if (e.message?.contains("No Activity found") == true) {
                "WhatsApp is not installed on this device."
            } else {
                "Failed to send file via WhatsApp: ${e.message}"
            }
        }
    }

    private fun findFileUri(name: String): Triple<Uri, String, String>? {
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: name
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "*/*"
                    val contentUri = android.content.ContentUris.withAppendedId(uri, id)
                    return Triple(contentUri, displayName, mime)
                }
            }
        } catch (_: Exception) {}
        return null
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

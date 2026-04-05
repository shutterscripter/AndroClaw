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
class PhoneCallToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    fun execute(input: Map<String, Any>): String {
        var phoneNumber = input["phone_number"] as? String
        val contactName = input["contact_name"] as? String
        val useSpeaker = input["speaker"] as? Boolean ?: false

        // Resolve contact name to number
        if (phoneNumber == null && contactName != null) {
            if (!permissionHelper.hasContactsPermission(context)) {
                return "Contacts permission not granted. Cannot look up \"$contactName\"."
            }
            val results = searchContacts(contactName)
            if (results.isEmpty()) {
                return "No contact found matching \"$contactName\". Try providing the phone number directly."
            }
            if (results.size > 1) {
                val list = results.take(5).joinToString("\n") { "- ${it.first}: ${it.second}" }
                // Use the first match but inform about alternatives
                phoneNumber = results.first().second
                return try {
                    makeCall(phoneNumber)
                    "Calling ${results.first().first} (${phoneNumber}).\n\nOther matches:\n$list"
                } catch (e: Exception) {
                    "Found multiple contacts:\n$list\nFailed to call: ${e.message}"
                }
            }
            phoneNumber = results.first().second
        }

        if (phoneNumber == null) {
            return "Please provide a phone number or contact name to call."
        }

        return try {
            makeCall(phoneNumber)
            val displayName = contactName ?: phoneNumber
            "Calling $displayName ($phoneNumber)..."
        } catch (e: Exception) {
            "Failed to make call: ${e.message}"
        }
    }

    private fun makeCall(phoneNumber: String) {
        val cleanNumber = phoneNumber.replace("[^0-9+*#]".toRegex(), "")
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(callIntent)
    }

    private fun searchContacts(query: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        // Try exact match first, then partial
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        val cursor = context.contentResolver.query(
            uri, projection, selection, selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return results

        cursor.use {
            val seenNames = mutableSetOf<String>()
            while (it.moveToNext() && results.size < 10) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val key = name.lowercase()
                if (key !in seenNames) {
                    seenNames.add(key)
                    results.add(name to number)
                }
            }
        }

        // Sort: exact matches first, then starts-with, then contains
        val q = query.lowercase()
        return results.sortedWith(compareBy(
            { !it.first.lowercase().equals(q) },
            { !it.first.lowercase().startsWith(q) },
            { it.first.lowercase() }
        ))
    }
}

package com.androclaw.tools

import android.content.Context
import android.provider.ContactsContract
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val permError = permissionHelper.ensurePermissionsForTool(context, "get_contacts")
        if (permError != null) return permError

        val nameQuery = input["name_query"] as? String ?: return "Missing name_query"

        return try {
            val contacts = searchContacts(nameQuery)
            if (contacts.isEmpty()) {
                "No contacts found matching \"$nameQuery\"."
            } else {
                val formatted = contacts.joinToString("\n") { (name, number) ->
                    "- $name: $number"
                }
                "Found ${contacts.size} contact(s):\n$formatted"
            }
        } catch (e: Exception) {
            "Failed to search contacts: ${e.message}"
        }
    }

    private fun searchContacts(query: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        val cursor = context.contentResolver.query(
            uri, projection, selection, selectionArgs, sortOrder
        ) ?: return results

        cursor.use {
            val seenNames = mutableSetOf<String>()
            while (it.moveToNext() && results.size < 10) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                if (name !in seenNames) {
                    seenNames.add(name)
                    results.add(name to number)
                }
            }
        }
        return results
    }
}

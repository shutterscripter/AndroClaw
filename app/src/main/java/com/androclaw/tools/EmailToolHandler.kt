package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmailToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val to = input["to"] as? String
        val subject = input["subject"] as? String ?: ""
        val body = input["body"] as? String ?: ""
        val cc = input["cc"] as? String
        val bcc = input["bcc"] as? String

        if (to == null) return "Missing email recipient (to)"

        return try {
            val toAddresses = to.split(",", ";").map { it.trim() }
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, toAddresses.toTypedArray())
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                cc?.let {
                    putExtra(Intent.EXTRA_CC, it.split(",", ";").map { a -> a.trim() }.toTypedArray())
                }
                bcc?.let {
                    putExtra(Intent.EXTRA_BCC, it.split(",", ";").map { a -> a.trim() }.toTypedArray())
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened email compose:\nTo: $to\nSubject: $subject\nBody: ${body.take(50)}..."
        } catch (e: Exception) {
            "Failed to open email: ${e.message}"
        }
    }
}

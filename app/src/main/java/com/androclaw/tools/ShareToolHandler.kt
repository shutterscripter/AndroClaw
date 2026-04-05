package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appCache: AppCacheManager
) {

    suspend fun execute(input: Map<String, Any>): String {
        val text = input["text"] as? String
        val url = input["url"] as? String
        val targetApp = input["target_app"] as? String

        if (text == null && url == null) {
            return "Please provide text or url to share."
        }

        val shareText = buildString {
            text?.let { append(it) }
            if (text != null && url != null) append("\n")
            url?.let { append(it) }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            url?.let { putExtra(Intent.EXTRA_SUBJECT, "Shared link") }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Resolve target app if specified
        if (targetApp != null) {
            val matches = appCache.findApp(targetApp)
            if (matches.isNotEmpty()) {
                intent.`package` = matches.first().packageName
                return try {
                    context.startActivity(intent)
                    "Sharing to ${matches.first().label}: \"${shareText.take(80)}...\""
                } catch (e: Exception) {
                    // Fall back to chooser
                    shareViaChooser(intent, shareText)
                }
            }
        }

        return shareViaChooser(intent, shareText)
    }

    private fun shareViaChooser(intent: Intent, content: String): String {
        return try {
            val chooser = Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            "Opened share dialog for: \"${content.take(80)}${if (content.length > 80) "..." else ""}\""
        } catch (e: Exception) {
            "Failed to share: ${e.message}"
        }
    }
}

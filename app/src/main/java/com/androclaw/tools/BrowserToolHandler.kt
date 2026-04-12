package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        // Legacy `search_query` param is no longer supported — it was causing
        // the model to open Google for factual questions instead of calling
        // web_search (which goes through Exa). Redirect the model.
        if (input["search_query"] != null && input["url"] == null) {
            return "browse_web no longer performs Google searches. Call the `web_search` tool instead — it returns real text results (via Exa when configured) you can reason about in-chat."
        }

        val url = input["url"] as? String
            ?: return "Please provide a 'url' to open. For factual questions, use web_search instead."

        val targetUrl = if (url.startsWith("http")) url else "https://$url"

        return try {
            val uri = Uri.parse(targetUrl)
            try {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
            } catch (_: Exception) {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }
            "Opened URL: $targetUrl"
        } catch (e: Exception) {
            "Failed to open browser: ${e.message}"
        }
    }
}

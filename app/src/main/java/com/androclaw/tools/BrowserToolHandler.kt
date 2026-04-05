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
        val url = input["url"] as? String
        val searchQuery = input["search_query"] as? String

        val targetUrl = when {
            searchQuery != null -> "https://www.google.com/search?q=${Uri.encode(searchQuery)}"
            url != null -> if (url.startsWith("http")) url else "https://$url"
            else -> return "Please provide either a URL or search query"
        }

        return try {
            val uri = Uri.parse(targetUrl)

            try {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
            } catch (e: Exception) {
                // Fallback to regular browser
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }

            if (searchQuery != null) {
                "Opened Google search for: \"$searchQuery\""
            } else {
                "Opened URL: $targetUrl"
            }
        } catch (e: Exception) {
            "Failed to open browser: ${e.message}"
        }
    }
}

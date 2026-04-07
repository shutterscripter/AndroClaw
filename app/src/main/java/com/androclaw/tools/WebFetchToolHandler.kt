package com.androclaw.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebFetchToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val maxContentLength = 8000

    suspend fun execute(input: Map<String, Any>): String {
        val url = input["url"] as? String
            ?: return "Missing 'url' parameter. Provide a URL to fetch."
        val extractMode = (input["extract_mode"] as? String)?.lowercase() ?: "text"

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Invalid URL. Must start with http:// or https://"
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val contentType = response.header("Content-Type") ?: ""

            // Handle non-HTML content
            if (!contentType.contains("text/html") && !contentType.contains("text/plain")
                && !contentType.contains("application/xhtml")) {
                response.body?.close()
                return "Non-HTML content at $url (type: $contentType). Cannot extract text."
            }

            val body = response.body?.string()
                ?: return "Empty response from $url"

            val doc = Jsoup.parse(body, url)

            when (extractMode) {
                "links" -> extractLinks(doc, url)
                "metadata" -> extractMetadata(doc, url)
                else -> extractText(doc, url)
            }
        } catch (e: Exception) {
            "Failed to fetch $url: ${e.message}"
        }
    }

    private fun extractText(doc: Document, url: String): String {
        // Remove non-content elements
        doc.select("script, style, nav, header, footer, aside, iframe, noscript, svg, form").remove()
        doc.select("[role=navigation], [role=banner], [role=complementary], [aria-hidden=true]").remove()
        doc.select(".nav, .menu, .sidebar, .footer, .header, .ad, .advertisement, .cookie").remove()

        val title = doc.title().ifBlank { "Untitled" }

        // Try to find main content area
        val mainContent = doc.selectFirst("article, main, [role=main], .post-content, .article-body, .entry-content, #content")
        val textSource = mainContent ?: doc.body() ?: return "No content found at $url"

        val text = textSource.text()
            .replace(Regex("\\s{3,}"), "\n\n")  // Collapse excessive whitespace
            .trim()

        if (text.isBlank()) {
            return "Page at $url has no readable text content."
        }

        val truncated = if (text.length > maxContentLength) {
            text.take(maxContentLength) + "\n\n... [Truncated — ${text.length - maxContentLength} more characters]"
        } else text

        return "Title: $title\nURL: $url\n\n$truncated"
    }

    private fun extractLinks(doc: Document, url: String): String {
        val links = doc.select("a[href]")
            .map { el ->
                val href = el.absUrl("href")
                val text = el.text().trim()
                if (href.isNotBlank() && text.isNotBlank() && href.startsWith("http")) {
                    "$text — $href"
                } else null
            }
            .filterNotNull()
            .distinct()
            .take(30)

        return if (links.isEmpty()) {
            "No links found at $url"
        } else {
            "Links from $url (${links.size}):\n" + links.joinToString("\n") { "- $it" }
        }
    }

    private fun extractMetadata(doc: Document, url: String): String {
        val title = doc.title()
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val author = doc.selectFirst("meta[name=author]")?.attr("content") ?: ""
        val publishDate = doc.selectFirst("meta[property=article:published_time]")?.attr("content")
            ?: doc.selectFirst("time[datetime]")?.attr("datetime") ?: ""
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href") ?: ""

        val sb = StringBuilder("Metadata for $url:\n")
        if (title.isNotBlank()) sb.appendLine("Title: $title")
        if (ogTitle.isNotBlank() && ogTitle != title) sb.appendLine("OG Title: $ogTitle")
        if (description.isNotBlank()) sb.appendLine("Description: $description")
        if (author.isNotBlank()) sb.appendLine("Author: $author")
        if (publishDate.isNotBlank()) sb.appendLine("Published: $publishDate")
        if (ogImage.isNotBlank()) sb.appendLine("Image: $ogImage")
        if (canonical.isNotBlank()) sb.appendLine("Canonical URL: $canonical")

        return sb.toString().trim()
    }
}

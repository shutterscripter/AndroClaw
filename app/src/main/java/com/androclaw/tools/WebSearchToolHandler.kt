package com.androclaw.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    suspend fun execute(input: Map<String, Any>): String {
        val query = input["query"] as? String
            ?: return "Missing 'query' parameter. Provide a search query."
        val maxResults = (input["max_results"] as? Number)?.toInt() ?: 5

        return try {
            // Try DuckDuckGo Instant Answer API first
            val ddgResults = searchDuckDuckGo(query, maxResults)
            if (ddgResults.isNotEmpty()) {
                formatResults(query, ddgResults)
            } else {
                // Fallback: scrape Google search results
                val googleResults = searchGoogle(query, maxResults)
                if (googleResults.isNotEmpty()) {
                    formatResults(query, googleResults)
                } else {
                    "No results found for \"$query\". Try a different search query."
                }
            }
        } catch (e: Exception) {
            "Web search failed: ${e.message}"
        }
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // DuckDuckGo Instant Answer API
        val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AndroClaw/1.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: return results

        try {
            val json = JSONObject(body)

            // Abstract (instant answer)
            val abstract = json.optString("Abstract", "")
            val abstractUrl = json.optString("AbstractURL", "")
            val abstractSource = json.optString("AbstractSource", "")
            if (abstract.isNotBlank()) {
                results.add(SearchResult(
                    title = abstractSource.ifBlank { "Summary" },
                    snippet = abstract,
                    url = abstractUrl
                ))
            }

            // Related topics
            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until minOf(relatedTopics.length(), maxResults)) {
                    val topic = relatedTopics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "")
                    val firstUrl = topic.optString("FirstURL", "")
                    if (text.isNotBlank()) {
                        results.add(SearchResult(
                            title = text.take(80),
                            snippet = text,
                            url = firstUrl
                        ))
                    }
                }
            }
        } catch (_: Exception) {}

        return results.take(maxResults)
    }

    private fun searchGoogle(query: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val doc = Jsoup.connect("https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .timeout(10_000)
                .get()

            // Parse search result blocks
            val resultElements = doc.select("div.g")
            for (element in resultElements) {
                if (results.size >= maxResults) break

                val titleEl = element.selectFirst("h3")
                val linkEl = element.selectFirst("a[href]")
                val snippetEl = element.selectFirst("div.VwiC3b, span.aCOpRe, div[data-sncf]")

                val title = titleEl?.text() ?: continue
                val link = linkEl?.attr("href") ?: ""
                val snippet = snippetEl?.text() ?: ""

                if (title.isNotBlank() && link.startsWith("http")) {
                    results.add(SearchResult(title = title, snippet = snippet, url = link))
                }
            }

            // Fallback: try broader selectors if no results found
            if (results.isEmpty()) {
                val links = doc.select("a[href]")
                for (link in links) {
                    if (results.size >= maxResults) break
                    val href = link.attr("href")
                    val text = link.text()
                    if (href.startsWith("http") && text.length > 15
                        && !href.contains("google.com") && !href.contains("accounts.google")) {
                        results.add(SearchResult(title = text, snippet = "", url = href))
                    }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    private fun formatResults(query: String, results: List<SearchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Search results for \"$query\" (${results.size} results):\n")
        for ((i, result) in results.withIndex()) {
            sb.appendLine("${i + 1}. ${result.title}")
            if (result.snippet.isNotBlank()) {
                sb.appendLine("   ${result.snippet}")
            }
            if (result.url.isNotBlank()) {
                sb.appendLine("   URL: ${result.url}")
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String)
}

package com.androclaw.tools

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.androclaw.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class WebSearchToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    @Named("encrypted") private val encryptedPrefs: SharedPreferences
) {

    suspend fun execute(input: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val query = input["query"] as? String
            ?: return@withContext "Missing 'query' parameter. Provide a search query."
        val maxResults = (input["max_results"] as? Number)?.toInt() ?: 5

        val exaKey = encryptedPrefs.getString(Constants.PREF_EXA_API_KEY, "")?.trim().orEmpty()
        Log.d("WebSearch", "execute: query='$query' exaKeyLen=${exaKey.length}")

        // When an Exa key is configured, Exa is the source of truth. We do NOT
        // silently fall through to DDG/Google on Exa errors — that was hiding
        // real failures (auth, rate limit, network) behind low-quality scraper
        // results. Instead we surface the error so the user can fix it.
        if (exaKey.isNotEmpty()) {
            return@withContext try {
                val exaResult = searchExa(query, maxResults, exaKey)
                if (exaResult.isNotEmpty()) {
                    Log.d("WebSearch", "Exa hit: ${exaResult.size} results")
                    formatResults(query, exaResult, providerLabel = "Exa")
                } else {
                    "Exa returned 0 results for \"$query\". Try a broader query."
                }
            } catch (e: Exception) {
                Log.e("WebSearch", "Exa failed", e)
                "Exa search failed: ${e.message}. Verify your Exa API key in Settings → Exa Web Search."
            }
        }

        // No Exa key — free-tier fallback stack
        try {
            val ddgResults = searchDuckDuckGo(query, maxResults)
            if (ddgResults.isNotEmpty()) {
                formatResults(query, ddgResults)
            } else {
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

    /**
     * Exa search — matches openclaw/extensions/exa:
     *   - POST https://api.exa.ai/search
     *   - headers: x-api-key, x-exa-integration, Accept, Content-Type
     *   - body: { query, numResults, type: "auto", contents: { highlights: true } }
     *   - description extracted from: highlights[] → summary → text (fallback)
     *   - throws on non-2xx with Exa's error body so the caller can show it
     */
    private fun searchExa(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        val payload = JSONObject().apply {
            put("query", query)
            put("numResults", maxResults.coerceIn(1, 100))
            put("type", "auto")
            put("contents", JSONObject().apply {
                put("highlights", JSONObject().apply { put("maxCharacters", 4000) })
            })
        }

        val request = Request.Builder()
            .url("https://api.exa.ai/search")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("x-exa-integration", "androclaw")
            .header("User-Agent", "AndroClaw/1.0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.d("WebSearch", "Exa HTTP ${response.code} bodyLen=${body.length}")
            if (!response.isSuccessful) {
                Log.w("WebSearch", "Exa error body: ${body.take(400)}")
                throw IllegalStateException("Exa API ${response.code}: ${body.take(200).ifBlank { response.message }}")
            }

            val json = JSONObject(body)
            val arr: JSONArray = json.optJSONArray("results")
                ?: throw IllegalStateException("Exa response missing 'results' field. Got keys: ${json.keys().asSequence().toList()}")

            val out = mutableListOf<SearchResult>()
            for (i in 0 until minOf(arr.length(), maxResults)) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "").ifBlank { item.optString("url", "") }
                val url = item.optString("url", "")
                val snippet = resolveExaDescription(item).take(500)
                if (title.isNotBlank() || url.isNotBlank()) {
                    out.add(SearchResult(title = title, snippet = snippet, url = url))
                }
            }
            Log.d("WebSearch", "Exa parsed ${out.size} usable results of ${arr.length()} raw")
            return out
        }
    }

    /** Mirrors openclaw resolveExaDescription: highlights[] → summary → text. */
    private fun resolveExaDescription(item: JSONObject): String {
        val highlights = item.optJSONArray("highlights")
        if (highlights != null && highlights.length() > 0) {
            val joined = buildString {
                for (i in 0 until highlights.length()) {
                    val s = highlights.optString(i, "").trim()
                    if (s.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(s)
                    }
                }
            }
            if (joined.isNotBlank()) return joined
        }
        val summary = item.optString("summary", "").trim()
        if (summary.isNotBlank()) return summary
        return item.optString("text", "").trim()
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

    /**
     * Format results so each item has a clean bold title, a cleaned snippet,
     * and a compact `[Read article →](url)` link button below. The chat UI's
     * inline markdown renderer turns that compact link into a tappable pill
     * — the raw URL never appears in the text.
     */
    private fun formatResults(query: String, results: List<SearchResult>, providerLabel: String? = null): String {
        val sb = StringBuilder()
        val header = if (providerLabel != null) "Search results for \"$query\" via $providerLabel" else "Search results for \"$query\""
        sb.append("## ").append(header).append(" (${results.size} results)\n\n")
        for ((i, result) in results.withIndex()) {
            val safeTitle = result.title.ifBlank { "Untitled" }
                .replace("]", "")
                .replace("[", "")
                .take(120)
            sb.append("${i + 1}. **").append(safeTitle).append("**\n")
            if (result.snippet.isNotBlank()) {
                val cleaned = result.snippet.replace(Regex("\\s+"), " ").trim().take(500)
                sb.append("   ").append(cleaned).append('\n')
            }
            if (result.url.isNotBlank()) {
                sb.append("   [Read article →](").append(result.url).append(")\n")
            }
            sb.append('\n')
        }
        sb.append("\nNote for the assistant: when replying to the user, keep the plain-text titles and snippets as-is, and ALWAYS include each `[Read article →](url)` link below its item so the user can tap to open the source. NEVER paste the bare URL inline — only the compact link button.")
        return sb.toString().trim()
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String)
}

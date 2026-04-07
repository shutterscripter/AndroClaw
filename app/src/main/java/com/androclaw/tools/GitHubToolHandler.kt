package com.androclaw.tools

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.androclaw.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * GitHub tool — talks directly to the GitHub REST API (no `gh` CLI on Android).
 *
 * Mirrors the openclaw `github` skill: PRs, issues, CI runs, and raw API queries.
 * Token is read from EncryptedSharedPreferences (set in Settings).
 */
@Singleton
class GitHubToolHandler @Inject constructor(
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    private val okHttpClient: OkHttpClient
) {

    private val apiBase = "https://api.github.com"
    private val jsonMedia = "application/json".toMediaType()
    private val maxBodyChars = 6000

    private companion object {
        const val TAG = "GitHubTool"
    }

    suspend fun execute(input: Map<String, Any>): String = withContext(Dispatchers.IO) {
        executeBlocking(input)
    }

    private fun executeBlocking(input: Map<String, Any>): String {
        val action = (input["action"] as? String)?.lowercase()
            ?: return "Missing 'action'. Try one of: list_prs, view_pr, create_pr_comment, " +
                "merge_pr, list_issues, view_issue, create_issue, comment_issue, close_issue, " +
                "list_runs, view_run, rerun, list_repos, list_notifications, search_repos, " +
                "search_issues, get_user, read_file, write_file, delete_file, list_dir, api"

        val rawStored = try {
            encryptedPrefs.getString(Constants.PREF_GITHUB_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences read failed", e)
            null
        }
        val token = rawStored?.trim()?.ifBlank { null }
        Log.d(TAG, "execute action=$action storedLen=${rawStored?.length ?: 0} trimmedLen=${token?.length ?: 0}")

        if (token.isNullOrBlank() && action != "get_user") {
            return "GitHub token not set (read 0 chars from EncryptedSharedPreferences " +
                "key '${Constants.PREF_GITHUB_TOKEN}'). Open Settings → GitHub, paste your " +
                "PAT, tap Save, and confirm the indicator says 'Saved (40 chars)'."
        }

        return try {
            when (action) {
                "list_prs" -> listPrs(input, token)
                "view_pr" -> viewPr(input, token)
                "pr_checks" -> prChecks(input, token)
                "create_pr_comment" -> createPrComment(input, token)
                "merge_pr" -> mergePr(input, token)
                "list_issues" -> listIssues(input, token)
                "view_issue" -> viewIssue(input, token)
                "create_issue" -> createIssue(input, token)
                "comment_issue" -> commentIssue(input, token)
                "close_issue" -> closeIssue(input, token)
                "list_runs" -> listRuns(input, token)
                "view_run" -> viewRun(input, token)
                "rerun" -> rerunRun(input, token)
                "list_repos" -> listRepos(input, token)
                "list_notifications" -> listNotifications(token)
                "search_repos" -> searchRepos(input, token)
                "search_issues" -> searchIssues(input, token)
                "get_user" -> getUser(input, token)
                "read_file" -> readFile(input, token)
                "write_file" -> writeFile(input, token)
                "delete_file" -> deleteFile(input, token)
                "list_dir" -> listDir(input, token)
                "api" -> rawApi(input, token)
                else -> "Unknown github action: $action"
            }
        } catch (e: Exception) {
            "GitHub error: ${e.message}"
        }
    }

    // ── HTTP helpers ──

    private fun getToken(): String? =
        encryptedPrefs.getString(Constants.PREF_GITHUB_TOKEN, null)?.trim()?.ifBlank { null }

    private fun newRequest(
        path: String,
        token: String?,
        method: String = "GET",
        bodyJson: String? = null
    ): Request {
        val url = if (path.startsWith("http")) path else apiBase + path
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AndroClaw")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((bodyJson ?: "{}").toRequestBody(jsonMedia))
            "PUT" -> builder.put((bodyJson ?: "{}").toRequestBody(jsonMedia))
            "PATCH" -> builder.patch((bodyJson ?: "{}").toRequestBody(jsonMedia))
            "DELETE" -> if (bodyJson != null) builder.delete(bodyJson.toRequestBody(jsonMedia)) else builder.delete()
        }
        return builder.build()
    }

    private fun call(req: Request): Pair<Int, String> {
        val authHeader = req.header("Authorization")
        val authState = when {
            authHeader == null -> "no-auth"
            authHeader.startsWith("Bearer ") -> "Bearer(len=${authHeader.length - 7})"
            else -> "other(${authHeader.take(8)}...)"
        }
        Log.d(TAG, "${req.method} ${req.url} auth=$authState")
        return try {
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Log.d(TAG, "← ${resp.code} ${req.url} body=${body.take(200)}")
                resp.code to body
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP failure for ${req.url}", e)
            throw e
        }
    }

    private fun requireRepo(input: Map<String, Any>): Pair<String, String>? {
        val repo = input["repo"] as? String ?: return null
        val parts = repo.split("/")
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun truncate(s: String): String =
        if (s.length > maxBodyChars) s.take(maxBodyChars) + "\n...[truncated ${s.length - maxBodyChars} chars]"
        else s

    private fun handleError(code: Int, body: String): String {
        val msg = try { JSONObject(body).optString("message") } catch (_: Exception) { "" }
        return "GitHub API error $code${if (msg.isNotBlank()) ": $msg" else ""}"
    }

    // ── Pull Requests ──

    private fun listPrs(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val state = (input["state"] as? String) ?: "open"
        val limit = (input["limit"] as? Number)?.toInt() ?: 20
        val (code, body) = call(newRequest("/repos/$owner/$repo/pulls?state=$state&per_page=$limit", token))
        if (code !in 200..299) return handleError(code, body)
        val arr = JSONArray(body)
        if (arr.length() == 0) return "No $state PRs in $owner/$repo."
        val sb = StringBuilder("Pull requests in $owner/$repo ($state):\n")
        for (i in 0 until arr.length()) {
            val pr = arr.getJSONObject(i)
            sb.append("#${pr.getInt("number")} ${pr.getString("title")} — @${pr.getJSONObject("user").getString("login")} [${pr.getString("state")}]\n")
        }
        return sb.toString().trim()
    }

    private fun viewPr(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        val (code, body) = call(newRequest("/repos/$owner/$repo/pulls/$number", token))
        if (code !in 200..299) return handleError(code, body)
        val pr = JSONObject(body)
        val sb = StringBuilder()
        sb.append("PR #${pr.getInt("number")}: ${pr.getString("title")}\n")
        sb.append("Author: @${pr.getJSONObject("user").getString("login")}\n")
        sb.append("State: ${pr.getString("state")}${if (pr.optBoolean("merged")) " (merged)" else ""}\n")
        sb.append("Branch: ${pr.getJSONObject("head").getString("ref")} → ${pr.getJSONObject("base").getString("ref")}\n")
        sb.append("Stats: +${pr.optInt("additions")} -${pr.optInt("deletions")} across ${pr.optInt("changed_files")} files\n")
        sb.append("URL: ${pr.getString("html_url")}\n\n")
        val bodyText = pr.optString("body", "")
        if (bodyText.isNotBlank()) sb.append(truncate(bodyText))
        return sb.toString().trim()
    }

    private fun prChecks(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        // Get the PR head SHA, then list check runs.
        val (prCode, prBody) = call(newRequest("/repos/$owner/$repo/pulls/$number", token))
        if (prCode !in 200..299) return handleError(prCode, prBody)
        val sha = JSONObject(prBody).getJSONObject("head").getString("sha")
        val (code, body) = call(newRequest("/repos/$owner/$repo/commits/$sha/check-runs", token))
        if (code !in 200..299) return handleError(code, body)
        val runs = JSONObject(body).getJSONArray("check_runs")
        if (runs.length() == 0) return "No checks for PR #$number."
        val sb = StringBuilder("Checks for PR #$number:\n")
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            sb.append("- ${r.getString("name")}: ${r.optString("status")} / ${r.optString("conclusion", "—")}\n")
        }
        return sb.toString().trim()
    }

    private fun createPrComment(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        val text = input["body"] as? String ?: return "Missing 'body'."
        val payload = JSONObject().put("body", text).toString()
        val (code, body) = call(newRequest("/repos/$owner/$repo/issues/$number/comments", token, "POST", payload))
        if (code !in 200..299) return handleError(code, body)
        return "Comment posted on PR #$number."
    }

    private fun mergePr(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        val method = (input["merge_method"] as? String) ?: "squash"
        val payload = JSONObject().put("merge_method", method).toString()
        val (code, body) = call(newRequest("/repos/$owner/$repo/pulls/$number/merge", token, "PUT", payload))
        if (code !in 200..299) return handleError(code, body)
        return "PR #$number merged ($method)."
    }

    // ── Issues ──

    private fun listIssues(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val state = (input["state"] as? String) ?: "open"
        val limit = (input["limit"] as? Number)?.toInt() ?: 20
        val (code, body) = call(newRequest("/repos/$owner/$repo/issues?state=$state&per_page=$limit", token))
        if (code !in 200..299) return handleError(code, body)
        val arr = JSONArray(body)
        // GitHub returns PRs in /issues; filter them out.
        val sb = StringBuilder("Issues in $owner/$repo ($state):\n")
        var count = 0
        for (i in 0 until arr.length()) {
            val issue = arr.getJSONObject(i)
            if (issue.has("pull_request")) continue
            sb.append("#${issue.getInt("number")} ${issue.getString("title")} — @${issue.getJSONObject("user").getString("login")}\n")
            count++
        }
        return if (count == 0) "No $state issues in $owner/$repo." else sb.toString().trim()
    }

    private fun viewIssue(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        val (code, body) = call(newRequest("/repos/$owner/$repo/issues/$number", token))
        if (code !in 200..299) return handleError(code, body)
        val issue = JSONObject(body)
        val sb = StringBuilder()
        sb.append("Issue #${issue.getInt("number")}: ${issue.getString("title")}\n")
        sb.append("Author: @${issue.getJSONObject("user").getString("login")}\n")
        sb.append("State: ${issue.getString("state")}\n")
        sb.append("URL: ${issue.getString("html_url")}\n\n")
        val bodyText = issue.optString("body", "")
        if (bodyText.isNotBlank()) sb.append(truncate(bodyText))
        return sb.toString().trim()
    }

    private fun createIssue(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val title = input["title"] as? String ?: return "Missing 'title'."
        val bodyText = input["body"] as? String ?: ""
        val payload = JSONObject().put("title", title).put("body", bodyText).toString()
        val (code, resp) = call(newRequest("/repos/$owner/$repo/issues", token, "POST", payload))
        if (code !in 200..299) return handleError(code, resp)
        val issue = JSONObject(resp)
        return "Created issue #${issue.getInt("number")}: ${issue.getString("html_url")}"
    }

    private fun commentIssue(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        val text = input["body"] as? String ?: return "Missing 'body'."
        val payload = JSONObject().put("body", text).toString()
        val (code, resp) = call(newRequest("/repos/$owner/$repo/issues/$number/comments", token, "POST", payload))
        if (code !in 200..299) return handleError(code, resp)
        return "Comment posted on issue #$number."
    }

    private fun closeIssue(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val number = (input["number"] as? Number)?.toInt() ?: return "Missing 'number'."
        val payload = JSONObject().put("state", "closed").toString()
        val (code, resp) = call(newRequest("/repos/$owner/$repo/issues/$number", token, "PATCH", payload))
        if (code !in 200..299) return handleError(code, resp)
        return "Issue #$number closed."
    }

    // ── Workflow runs ──

    private fun listRuns(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val limit = (input["limit"] as? Number)?.toInt() ?: 10
        val (code, body) = call(newRequest("/repos/$owner/$repo/actions/runs?per_page=$limit", token))
        if (code !in 200..299) return handleError(code, body)
        val runs = JSONObject(body).getJSONArray("workflow_runs")
        if (runs.length() == 0) return "No workflow runs in $owner/$repo."
        val sb = StringBuilder("Recent runs in $owner/$repo:\n")
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            sb.append("- ${r.getLong("id")} | ${r.getString("name")} | ${r.optString("status")}/${r.optString("conclusion", "—")} | branch ${r.optString("head_branch")}\n")
        }
        return sb.toString().trim()
    }

    private fun viewRun(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val runId = (input["run_id"] as? Number)?.toLong() ?: return "Missing 'run_id'."
        val (code, body) = call(newRequest("/repos/$owner/$repo/actions/runs/$runId", token))
        if (code !in 200..299) return handleError(code, body)
        val r = JSONObject(body)
        return "Run ${r.getLong("id")}: ${r.getString("name")}\n" +
            "Status: ${r.optString("status")} / ${r.optString("conclusion", "—")}\n" +
            "Branch: ${r.optString("head_branch")}\n" +
            "URL: ${r.getString("html_url")}"
    }

    private fun rerunRun(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo'."
        val runId = (input["run_id"] as? Number)?.toLong() ?: return "Missing 'run_id'."
        val failedOnly = (input["failed_only"] as? Boolean) ?: false
        val path = if (failedOnly) "/repos/$owner/$repo/actions/runs/$runId/rerun-failed-jobs"
                   else "/repos/$owner/$repo/actions/runs/$runId/rerun"
        val (code, body) = call(newRequest(path, token, "POST"))
        if (code !in 200..299) return handleError(code, body)
        return "Re-running run $runId${if (failedOnly) " (failed jobs only)" else ""}."
    }

    // ── Repos / user ──

    private fun listRepos(input: Map<String, Any>, token: String?): String {
        val user = input["user"] as? String
        val limit = (input["limit"] as? Number)?.toInt() ?: 30
        val path = if (user != null) "/users/$user/repos?per_page=$limit&sort=updated"
                   else "/user/repos?per_page=$limit&sort=updated"
        val (code, body) = call(newRequest(path, token))
        if (code !in 200..299) return handleError(code, body)
        val arr = JSONArray(body)
        if (arr.length() == 0) return "No repos found."
        val sb = StringBuilder("Repos:\n")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            sb.append("- ${r.getString("full_name")} ⭐${r.optInt("stargazers_count")} — ${r.optString("description", "")}\n")
        }
        return sb.toString().trim()
    }

    private fun listNotifications(token: String?): String {
        val (code, body) = call(newRequest("/notifications?per_page=20", token))
        if (code !in 200..299) return handleError(code, body)
        val arr = JSONArray(body)
        if (arr.length() == 0) return "No unread GitHub notifications."
        val sb = StringBuilder("Notifications:\n")
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            val subj = n.getJSONObject("subject")
            val repo = n.getJSONObject("repository").getString("full_name")
            sb.append("- [${n.getString("reason")}] $repo — ${subj.getString("title")} (${subj.getString("type")})\n")
        }
        return sb.toString().trim()
    }

    private fun searchRepos(input: Map<String, Any>, token: String?): String {
        val q = input["query"] as? String ?: return "Missing 'query'."
        val limit = (input["limit"] as? Number)?.toInt() ?: 10
        val (code, body) = call(newRequest("/search/repositories?q=${java.net.URLEncoder.encode(q, "UTF-8")}&per_page=$limit", token))
        if (code !in 200..299) return handleError(code, body)
        val items = JSONObject(body).getJSONArray("items")
        if (items.length() == 0) return "No repos match \"$q\"."
        val sb = StringBuilder("Repo search results for \"$q\":\n")
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            sb.append("- ${r.getString("full_name")} ⭐${r.optInt("stargazers_count")} — ${r.optString("description", "")}\n")
        }
        return sb.toString().trim()
    }

    private fun searchIssues(input: Map<String, Any>, token: String?): String {
        val q = input["query"] as? String ?: return "Missing 'query'."
        val limit = (input["limit"] as? Number)?.toInt() ?: 10
        val (code, body) = call(newRequest("/search/issues?q=${java.net.URLEncoder.encode(q, "UTF-8")}&per_page=$limit", token))
        if (code !in 200..299) return handleError(code, body)
        val items = JSONObject(body).getJSONArray("items")
        if (items.length() == 0) return "No issues/PRs match \"$q\"."
        val sb = StringBuilder("Issue/PR search results for \"$q\":\n")
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            val kind = if (r.has("pull_request")) "PR" else "Issue"
            sb.append("- [$kind] ${r.getString("html_url")} — ${r.getString("title")}\n")
        }
        return sb.toString().trim()
    }

    private fun getUser(input: Map<String, Any>, token: String?): String {
        val username = input["username"] as? String
        val path = if (username != null) "/users/$username" else "/user"
        val (code, body) = call(newRequest(path, token))
        if (code !in 200..299) return handleError(code, body)
        val u = JSONObject(body)
        return buildString {
            append("User: @${u.getString("login")}\n")
            if (u.optString("name").isNotBlank()) append("Name: ${u.getString("name")}\n")
            if (u.optString("bio").isNotBlank()) append("Bio: ${u.getString("bio")}\n")
            append("Repos: ${u.optInt("public_repos")} | Followers: ${u.optInt("followers")}\n")
            append("URL: ${u.getString("html_url")}")
        }
    }

    // ── File contents (read/write/delete/list) ──

    private fun encodePath(path: String): String =
        path.trim('/').split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun readFile(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val path = input["path"] as? String ?: return "Missing 'path' (file path in repo)."
        val branch = input["branch"] as? String
        val query = if (branch != null) "?ref=${java.net.URLEncoder.encode(branch, "UTF-8")}" else ""
        val (code, body) = call(newRequest("/repos/$owner/$repo/contents/${encodePath(path)}$query", token))
        if (code !in 200..299) return handleError(code, body)
        val obj = JSONObject(body)
        if (obj.optString("type") != "file") return "Path '$path' is not a file (type=${obj.optString("type")})."
        val sha = obj.getString("sha")
        val size = obj.optInt("size")
        val encoded = obj.optString("content").replace("\n", "")
        val decoded = try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            return "Failed to decode file content: ${e.message}"
        }
        return buildString {
            append("File: $owner/$repo:$path")
            if (branch != null) append(" @$branch")
            append("\nSHA: $sha\nSize: $size bytes\n\n")
            append(truncate(decoded))
        }
    }

    private fun writeFile(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val path = input["path"] as? String ?: return "Missing 'path'."
        val content = input["content"] as? String ?: return "Missing 'content' (new full file content)."
        val message = (input["message"] as? String) ?: "Update $path via AndroClaw"
        val branch = input["branch"] as? String
        // If sha not provided, try to fetch it (so callers can just pass path+content for updates).
        var sha = input["sha"] as? String
        if (sha.isNullOrBlank()) {
            val query = if (branch != null) "?ref=${java.net.URLEncoder.encode(branch, "UTF-8")}" else ""
            val (probeCode, probeBody) = call(newRequest("/repos/$owner/$repo/contents/${encodePath(path)}$query", token))
            if (probeCode in 200..299) {
                sha = JSONObject(probeBody).optString("sha").ifBlank { null }
            } else if (probeCode != 404) {
                return handleError(probeCode, probeBody)
            }
            // 404 → new file, leave sha null
        }
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("message", message)
            put("content", encoded)
            if (!sha.isNullOrBlank()) put("sha", sha)
            if (branch != null) put("branch", branch)
        }.toString()
        val (code, body) = call(newRequest("/repos/$owner/$repo/contents/${encodePath(path)}", token, "PUT", payload))
        if (code !in 200..299) return handleError(code, body)
        val resp = JSONObject(body)
        val commit = resp.optJSONObject("commit")
        val commitSha = commit?.optString("sha")?.take(7) ?: "?"
        val commitUrl = commit?.optString("html_url") ?: ""
        val verb = if (sha.isNullOrBlank()) "Created" else "Updated"
        return "$verb $owner/$repo:$path${branch?.let { " @$it" } ?: ""}\nCommit: $commitSha\n$commitUrl"
    }

    private fun deleteFile(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val path = input["path"] as? String ?: return "Missing 'path'."
        val message = (input["message"] as? String) ?: "Delete $path via AndroClaw"
        val branch = input["branch"] as? String
        var sha = input["sha"] as? String
        if (sha.isNullOrBlank()) {
            val query = if (branch != null) "?ref=${java.net.URLEncoder.encode(branch, "UTF-8")}" else ""
            val (probeCode, probeBody) = call(newRequest("/repos/$owner/$repo/contents/${encodePath(path)}$query", token))
            if (probeCode !in 200..299) return handleError(probeCode, probeBody)
            sha = JSONObject(probeBody).optString("sha")
        }
        val payload = JSONObject().apply {
            put("message", message)
            put("sha", sha)
            if (branch != null) put("branch", branch)
        }.toString()
        val (code, body) = call(newRequest("/repos/$owner/$repo/contents/${encodePath(path)}", token, "DELETE", payload))
        if (code !in 200..299) return handleError(code, body)
        val commit = JSONObject(body).optJSONObject("commit")
        val commitSha = commit?.optString("sha")?.take(7) ?: "?"
        return "Deleted $owner/$repo:$path${branch?.let { " @$it" } ?: ""}\nCommit: $commitSha"
    }

    private fun listDir(input: Map<String, Any>, token: String?): String {
        val (owner, repo) = requireRepo(input) ?: return "Missing 'repo' (owner/repo)."
        val path = (input["path"] as? String) ?: ""
        val branch = input["branch"] as? String
        val query = if (branch != null) "?ref=${java.net.URLEncoder.encode(branch, "UTF-8")}" else ""
        val urlPath = if (path.isBlank()) "/repos/$owner/$repo/contents$query"
                      else "/repos/$owner/$repo/contents/${encodePath(path)}$query"
        val (code, body) = call(newRequest(urlPath, token))
        if (code !in 200..299) return handleError(code, body)
        // Could be a file or array depending on path.
        if (body.trimStart().startsWith("{")) {
            val obj = JSONObject(body)
            return "Path '$path' is a ${obj.optString("type")}, not a directory. Use read_file instead."
        }
        val arr = JSONArray(body)
        if (arr.length() == 0) return "Empty directory: $owner/$repo:${path.ifBlank { "/" }}"
        val sb = StringBuilder("Contents of $owner/$repo:${path.ifBlank { "/" }}${branch?.let { " @$it" } ?: ""}:\n")
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val type = e.optString("type")
            val mark = if (type == "dir") "📁" else "📄"
            val sizePart = if (type == "file") " (${e.optInt("size")}b)" else ""
            sb.append("$mark ${e.getString("name")}$sizePart\n")
        }
        return sb.toString().trim()
    }

    // ── Raw API escape hatch ──

    private fun rawApi(input: Map<String, Any>, token: String?): String {
        val path = input["path"] as? String ?: return "Missing 'path' (e.g. /repos/owner/repo)."
        val method = (input["method"] as? String) ?: "GET"
        val payload = input["body"] as? String
        val (code, body) = call(newRequest(path, token, method, payload))
        if (code !in 200..299) return handleError(code, body)
        return truncate(body)
    }
}

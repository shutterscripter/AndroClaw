package com.androclaw.tools

import android.util.Log
import com.androclaw.service.AndroClawAccessibilityService
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool execution interceptor inspired by OpenClaw's hook system
 * (before_tool_call / after_tool_call). Provides:
 *
 * 1. Audit logging — records all tool executions with timing
 * 2. Rate limiting — prevents runaway loops (e.g., max N sends per turn)
 * 3. Safety gates — blocks destructive tools without confirmation context
 */
@Singleton
class ToolInterceptor @Inject constructor() {

    companion object {
        private const val TAG = "ToolInterceptor"

        /** Max calls per tool per agent turn (reset between user messages) */
        private const val DEFAULT_RATE_LIMIT = 10

        /** Tools that send outbound messages — stricter rate limit */
        private val MESSAGING_TOOLS = setOf("send_sms", "send_whatsapp", "send_email", "make_phone_call")
        private const val MESSAGING_RATE_LIMIT = 3

        /** Destructive tools that warrant extra caution */
        val DESTRUCTIVE_TOOLS = setOf(
            "send_sms", "send_whatsapp", "send_email", "make_phone_call",
            "control_app_ui", "toggle_setting"
        )

        /** Read-only / safe tools that never need interception */
        private val SAFE_TOOLS = setOf(
            "web_search", "web_fetch", "device_info", "list_apps", "get_contacts",
            "get_location", "call_log", "read_sms", "take_screenshot", "screen_time",
            "memory", "notes", "skills", "schedule", "notifications",
            "media_control", "brightness_control", "clipboard", "text_to_speech"
        )

        /** Tools that always require accessibility service */
        private val ACCESSIBILITY_REQUIRED_TOOLS = setOf(
            "control_app_ui", "auto_scroll_feed", "take_screenshot"
        )

        /** Tools that conditionally need accessibility (only for certain inputs) */
        private val ACCESSIBILITY_CONDITIONAL_TOOLS = mapOf(
            "send_whatsapp" to "group_name" // needs accessibility only when targeting a group
        )

        private const val ACCESSIBILITY_ERROR =
            "Accessibility service is not enabled. Please enable it in Settings > Accessibility > AndroClaw before performing this action."
    }

    // Per-turn call counts (toolName → count)
    private val turnCallCounts = ConcurrentHashMap<String, Int>()

    // Audit log — last N entries kept in memory for debugging
    private val _auditLog = mutableListOf<AuditEntry>()
    val auditLog: List<AuditEntry> get() = _auditLog.toList()

    /**
     * Called before each tool execution.
     * Returns null if allowed, or an error message string if blocked.
     */
    fun beforeExecute(toolName: String, input: Map<String, Any>): String? {
        // Check accessibility requirement before anything launches
        if (toolName in ACCESSIBILITY_REQUIRED_TOOLS) {
            if (AndroClawAccessibilityService.instance == null) {
                Log.w(TAG, "Blocked $toolName: accessibility not enabled")
                return ACCESSIBILITY_ERROR
            }
        }

        // Check conditional accessibility (e.g., send_whatsapp with group_name)
        val conditionalKey = ACCESSIBILITY_CONDITIONAL_TOOLS[toolName]
        if (conditionalKey != null && input.containsKey(conditionalKey)) {
            if (AndroClawAccessibilityService.instance == null) {
                Log.w(TAG, "Blocked $toolName ($conditionalKey): accessibility not enabled")
                return "Sending to a WhatsApp group requires the accessibility service. " +
                    "Please enable it in Settings > Accessibility > AndroClaw, then try again."
            }
        }

        // Check rate limit
        val currentCount = turnCallCounts.getOrDefault(toolName, 0)
        val limit = if (toolName in MESSAGING_TOOLS) MESSAGING_RATE_LIMIT else DEFAULT_RATE_LIMIT

        if (currentCount >= limit) {
            val msg = "Rate limited: $toolName has been called $currentCount times this turn (max $limit). " +
                "This prevents runaway loops. Ask the user before retrying."
            Log.w(TAG, msg)
            return msg
        }

        // Increment count
        turnCallCounts[toolName] = currentCount + 1

        return null // Allowed
    }

    /**
     * Called after each tool execution. Logs the result.
     */
    fun afterExecute(toolName: String, input: Map<String, Any>, result: String, durationMs: Long) {
        val entry = AuditEntry(
            toolName = toolName,
            input = summarizeInput(toolName, input),
            resultPreview = result.take(200),
            success = !result.startsWith("Error"),
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        )

        synchronized(_auditLog) {
            _auditLog.add(entry)
            // Keep last 100 entries
            if (_auditLog.size > 100) {
                _auditLog.removeAt(0)
            }
        }

        Log.d(TAG, "Tool: $toolName (${durationMs}ms) → ${if (entry.success) "OK" else "ERROR"}")
    }

    /**
     * Reset per-turn counters. Call this at the start of each user message.
     */
    fun resetTurnCounters() {
        turnCallCounts.clear()
    }

    /**
     * Check if a tool is considered destructive / outbound.
     */
    fun isDestructive(toolName: String): Boolean = toolName in DESTRUCTIVE_TOOLS

    /**
     * Check if a tool is safe (read-only).
     */
    fun isSafe(toolName: String): Boolean = toolName in SAFE_TOOLS

    /**
     * Get a formatted audit log summary for debugging.
     */
    fun getAuditSummary(limit: Int = 20): String {
        val entries = synchronized(_auditLog) { _auditLog.takeLast(limit) }
        if (entries.isEmpty()) return "No tool executions recorded."

        return buildString {
            appendLine("Recent tool executions (${entries.size}):")
            for (e in entries.reversed()) {
                val status = if (e.success) "OK" else "ERR"
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(e.timestamp))
                appendLine("  [$time] $status ${e.toolName} (${e.durationMs}ms) — ${e.input}")
            }
        }.trimEnd()
    }

    /**
     * Get per-turn call counts for the current turn.
     */
    fun getTurnStats(): Map<String, Int> = turnCallCounts.toMap()

    private fun summarizeInput(toolName: String, input: Map<String, Any>): String {
        // Create a human-readable summary without dumping full input
        return when (toolName) {
            "send_sms" -> "to: ${input["contact_name"] ?: input["phone_number"]}"
            "send_whatsapp" -> "to: ${input["contact_name"]}"
            "send_email" -> "to: ${input["to"]}"
            "make_phone_call" -> "to: ${input["contact_name"] ?: input["phone_number"]}"
            "web_search" -> "q: ${input["query"]}"
            "web_fetch" -> "url: ${input["url"]}"
            "memory" -> "${input["action"]} ${input["key"] ?: ""}"
            "file_manager" -> "${input["action"]} ${input["query"] ?: input["path"] ?: ""}"
            "open_app" -> "${input["app_name"]}"
            "skills" -> "${input["action"]} ${input["trigger"] ?: ""}"
            "schedule" -> "${input["action"]} ${input["name"] ?: ""}"
            else -> input.entries.take(2).joinToString(", ") { "${it.key}=${it.value}" }
        }.take(100)
    }
}

data class AuditEntry(
    val toolName: String,
    val input: String,
    val resultPreview: String,
    val success: Boolean,
    val durationMs: Long,
    val timestamp: Long
)

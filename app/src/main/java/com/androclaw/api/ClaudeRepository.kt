package com.androclaw.api

import android.content.SharedPreferences
import com.androclaw.api.models.ContentBlock
import com.androclaw.api.models.Message
import com.androclaw.api.provider.LlmProvider
import com.androclaw.api.provider.LlmResponse
import com.androclaw.api.provider.ProviderRegistry
import com.androclaw.api.provider.StreamChunk
import com.androclaw.tools.ToolExecutor
import com.androclaw.utils.Constants
import com.androclaw.utils.NetworkErrors
import com.androclaw.utils.isLikelyConnectivityFailure
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ToolStatus(val toolName: String, val description: String, val isComplete: Boolean = false)

sealed class AgentEvent {
    data class ToolExecuting(val status: ToolStatus) : AgentEvent()
    data class ToolCompleted(val status: ToolStatus) : AgentEvent()
    data class FinalResponse(val text: String) : AgentEvent()
    data class StreamingText(val delta: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class ContextUpdate(val info: ContextUsageInfo) : AgentEvent()
    data class Compacting(val message: String) : AgentEvent()
}

@Singleton
class ClaudeRepository @Inject constructor(
    private val toolExecutor: ToolExecutor,
    private val systemPromptManager: SystemPromptManager,
    private val providerRegistry: ProviderRegistry,
    private val contextManager: ContextManager,
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    @Named("regular") private val prefs: SharedPreferences
) {
    // Use SharedFlow (not StateFlow) so streaming token deltas are NOT conflated.
    // StateFlow drops intermediate values when emissions arrive faster than the
    // collector — that breaks token-by-token streaming UX. SUSPEND backpressure
    // pauses the producer if the UI ever falls behind, so no deltas are lost.
    private val _agentEvents = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val agentEvents: SharedFlow<AgentEvent> = _agentEvents

    private fun getApiKey(): String? {
        val providerId = getProvider()
        // Try per-provider key first, fall back to legacy key
        val perProviderKey = encryptedPrefs.getString("api_key_$providerId", null)
        if (!perProviderKey.isNullOrBlank()) return perProviderKey
        return encryptedPrefs.getString(Constants.PREF_API_KEY, null)
    }

    fun getProvider(): String =
        prefs.getString(Constants.PREF_PROVIDER, "claude") ?: "claude"

    private fun getModel(): String {
        val saved = prefs.getString(Constants.PREF_MODEL, Constants.DEFAULT_MODEL) ?: Constants.DEFAULT_MODEL
        return saved
    }

    private fun getEnabledTools(): Set<String> =
        prefs.getStringSet(Constants.PREF_ENABLED_TOOLS, Constants.ALL_TOOL_NAMES.toSet())
            ?: Constants.ALL_TOOL_NAMES.toSet()

    private fun resolveProvider(): LlmProvider? {
        val providerId = getProvider()
        return providerRegistry.getProvider(providerId)
    }

    suspend fun sendMessage(
        conversationHistory: MutableList<Message>,
        userText: String
    ): Result<String> {
        val apiKey = getApiKey()
            ?: return Result.failure(Exception("API key not set. Please go to Settings to add your API key."))

        val provider = resolveProvider()
            ?: return Result.failure(Exception("Provider \"${getProvider()}\" not found."))

        return try {
            // Reset per-turn interceptor counters
            toolExecutor.interceptor.resetTurnCounters()

            // Add user message
            conversationHistory.add(Message(role = "user", content = userText))

            val tools = ToolDefinitions.getAllTools(getEnabledTools())
            var attempts = 0
            val maxLoopIterations = 10

            val systemPrompt = buildSystemPrompt()
            val model = getModel()
            val providerId = getProvider()
            // Streaming is always on when the provider supports it — there is no
            // user toggle. Providers that don't support SSE fall through to the
            // non-streaming path automatically.
            val streaming = provider.supportsStreaming

            // Prune verbose tool results from older messages
            contextManager.pruneOldToolResults(conversationHistory)

            // Auto-compact if context window is getting full
            if (contextManager.needsCompaction(systemPrompt, conversationHistory, providerId, model)) {
                val plan = contextManager.prepareCompaction(conversationHistory)
                if (plan != null) {
                    _agentEvents.emit(AgentEvent.Compacting("Summarizing older messages to free up context..."))
                    try {
                        val summaryResponse = if (streaming) {
                            sendStreaming(provider, apiKey, model, "You are a helpful assistant that summarizes conversations.",
                                listOf(Message(role = "user", content = plan.summaryPrompt)), emptyList())
                        } else {
                            sendWithRetry(provider, apiKey, model, "You are a helpful assistant that summarizes conversations.",
                                listOf(Message(role = "user", content = plan.summaryPrompt)), emptyList()
                            ).getOrNull()
                        }
                        val summary = summaryResponse?.content
                            ?.filter { it.type == "text" }
                            ?.joinToString("\n") { it.text ?: "" }
                            ?.trim()

                        if (!summary.isNullOrBlank()) {
                            contextManager.applyCompaction(conversationHistory, summary, plan)
                        }
                    } catch (_: Exception) {
                        // Compaction failed — continue with full history
                    }
                }
            }

            // Emit context usage info
            val usageInfo = contextManager.getUsageInfo(systemPrompt, conversationHistory, providerId, model)
            _agentEvents.emit(AgentEvent.ContextUpdate(usageInfo))

            while (attempts < maxLoopIterations) {

                val response: LlmResponse = if (streaming) {
                    sendStreaming(provider, apiKey, model, systemPrompt, conversationHistory, tools)
                } else {
                    val nr = sendWithRetry(provider, apiKey, model, systemPrompt, conversationHistory, tools)
                    if (nr.isFailure) {
                        return Result.failure(nr.exceptionOrNull() as? Exception ?: Exception(nr.exceptionOrNull()?.message))
                    }
                    nr.getOrThrow()
                }

                // Add assistant response to history
                val assistantContent = response.content.map { block ->
                    when (block.type) {
                        "text" -> mapOf("type" to "text", "text" to (block.text ?: ""))
                        "tool_use" -> {
                            val map = mutableMapOf<String, Any>(
                                "type" to "tool_use",
                                "id" to (block.id ?: ""),
                                "name" to (block.name ?: "")
                            )
                            block.input?.let { map["input"] = it }
                            map
                        }
                        else -> mapOf("type" to block.type)
                    }
                }
                conversationHistory.add(Message(role = "assistant", content = assistantContent))

                // Check if there are tool uses
                val toolUseBlocks = response.content.filter { it.type == "tool_use" }

                if (toolUseBlocks.isEmpty() || response.stopReason == "end_turn" || response.stopReason == "stop") {
                    val finalText = response.content
                        .filter { it.type == "text" }
                        .joinToString("\n") { it.text ?: "" }
                        .trim()

                    _agentEvents.emit(AgentEvent.FinalResponse(finalText))
                    return Result.success(finalText)
                }

                // Execute tools
                val toolResults = mutableListOf<Map<String, Any>>()

                for (block in toolUseBlocks) {
                    val toolName = block.name ?: continue
                    val toolId = block.id ?: continue
                    val toolInput = block.input ?: emptyMap()

                    val description = describeToolCall(toolName, toolInput)
                    _agentEvents.emit(AgentEvent.ToolExecuting(ToolStatus(toolName, description)))

                    val result = try {
                        toolExecutor.execute(toolName, toolInput)
                    } catch (e: Exception) {
                        "Error executing $toolName: ${e.message}"
                    }

                    _agentEvents.emit(AgentEvent.ToolCompleted(ToolStatus(toolName, description, isComplete = true)))
                    toolResults.add(buildToolResultBlock(toolId, result))
                }

                conversationHistory.add(Message(role = "user", content = toolResults))
                attempts++
            }

            Result.failure(Exception("Agent exceeded maximum tool use iterations."))
        } catch (e: Exception) {
            val msg = if (e.isLikelyConnectivityFailure()) {
                NetworkErrors.NO_CONNECTION_USER_MESSAGE
            } else {
                e.message ?: "Unknown error"
            }
            Result.failure(Exception(msg))
        }
    }

    // ── Streaming ──────────────────────────────────────────────────

    private suspend fun sendStreaming(
        provider: LlmProvider,
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Message>,
        tools: List<com.androclaw.api.models.ToolDefinition>
    ): LlmResponse {
        val textBuilder = StringBuilder()
        val contentBlocks = mutableListOf<ContentBlock>()
        var stopReason: String? = null

        // Track tool calls being assembled from stream
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolInputBuilder = StringBuilder()

        provider.streamMessage(apiKey, model, systemPrompt, messages, tools.ifEmpty { null }, Constants.MAX_TOKENS)
            .collect { chunk ->
                when (chunk) {
                    is StreamChunk.TextDelta -> {
                        textBuilder.append(chunk.text)
                        _agentEvents.emit(AgentEvent.StreamingText(chunk.text))
                    }
                    is StreamChunk.ToolUseStart -> {
                        // Flush any accumulated text
                        if (textBuilder.isNotEmpty()) {
                            contentBlocks.add(ContentBlock(type = "text", text = textBuilder.toString()))
                            textBuilder.clear()
                        }
                        currentToolId = chunk.id
                        currentToolName = chunk.name
                        toolInputBuilder.clear()
                    }
                    is StreamChunk.ToolInputDelta -> {
                        toolInputBuilder.append(chunk.json)
                    }
                    is StreamChunk.ToolUseEnd -> {
                        val input = try {
                            val json = JSONObject(toolInputBuilder.toString())
                            jsonObjectToMap(json)
                        } catch (_: Exception) { emptyMap() }

                        contentBlocks.add(ContentBlock(
                            type = "tool_use",
                            id = currentToolId,
                            name = currentToolName,
                            input = input
                        ))
                        currentToolId = null
                        currentToolName = null
                        toolInputBuilder.clear()
                    }
                    is StreamChunk.Done -> {
                        stopReason = chunk.stopReason
                    }
                    is StreamChunk.Error -> {
                        throw Exception(chunk.message)
                    }
                }
            }

        // Flush remaining text
        if (textBuilder.isNotEmpty()) {
            contentBlocks.add(ContentBlock(type = "text", text = textBuilder.toString()))
        }

        return LlmResponse(content = contentBlocks, stopReason = stopReason, model = model)
    }

    // ── Non-streaming with retry ──────────────────────────────────

    private suspend fun sendWithRetry(
        provider: LlmProvider,
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Message>,
        tools: List<com.androclaw.api.models.ToolDefinition>,
        maxRetries: Int = 3
    ): Result<LlmResponse> {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val result = provider.sendMessage(apiKey, model, systemPrompt, messages, tools.ifEmpty { null }, Constants.MAX_TOKENS)
                result.fold(
                    onSuccess = { return Result.success(it) },
                    onFailure = { e ->
                        lastError = e as? Exception ?: Exception(e.message)
                        val msg = e.message ?: ""
                        val displayMsg = if (e.isLikelyConnectivityFailure()) {
                            NetworkErrors.NO_CONNECTION_USER_MESSAGE
                        } else {
                            msg
                        }
                        if (msg.contains("429") || msg.contains("500") || msg.contains("502") || msg.contains("503")) {
                            delay(1000L * (attempt + 1))
                        } else {
                            return Result.failure(Exception(displayMsg))
                        }
                    }
                )
            } catch (e: Exception) {
                lastError = e as? Exception ?: Exception(e.message)
                if (e.isLikelyConnectivityFailure()) {
                    return Result.failure(Exception(NetworkErrors.NO_CONNECTION_USER_MESSAGE))
                }
                delay(1000L * (attempt + 1))
            }
        }
        val fallbackMsg = lastError?.let { err ->
            if (err.isLikelyConnectivityFailure()) NetworkErrors.NO_CONNECTION_USER_MESSAGE
            else err.message
        } ?: "Unknown error"
        return Result.failure(Exception(fallbackMsg))
    }

    // ── Helpers ──────────────────────────────────────────────────

    private suspend fun buildSystemPrompt(): String {
        return systemPromptManager.buildSystemPrompt()
    }

    private fun buildToolResultBlock(toolId: String, result: String): Map<String, Any> {
        val imagePrefix = "[IMAGE_BASE64:"
        if (result.startsWith(imagePrefix)) {
            val closeBracket = result.indexOf(']')
            if (closeBracket > imagePrefix.length) {
                val mediaType = result.substring(imagePrefix.length, closeBracket)
                // Optional ||LEGEND|| separator: anything after it is rendered
                // as a sibling text block alongside the image, used by
                // screen_observe to ship the marked-up screenshot AND its
                // numbered element legend in a single tool_result.
                val rest = result.substring(closeBracket + 1)
                val legendSep = "||LEGEND||"
                val (base64Data, legendText) = if (rest.contains(legendSep)) {
                    val idx = rest.indexOf(legendSep)
                    rest.substring(0, idx) to rest.substring(idx + legendSep.length)
                } else {
                    rest to "Screenshot captured. Analyze what you see on the screen."
                }

                val contentBlocks = listOf(
                    mapOf(
                        "type" to "image",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to mediaType,
                            "data" to base64Data
                        )
                    ),
                    mapOf(
                        "type" to "text",
                        "text" to legendText
                    )
                )

                return mapOf(
                    "type" to "tool_result",
                    "tool_use_id" to toolId,
                    "content" to contentBlocks
                )
            }
        }

        return mapOf(
            "type" to "tool_result",
            "tool_use_id" to toolId,
            "content" to result
        )
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is org.json.JSONArray -> (0 until value.length()).map {
                    val v = value.get(it)
                    if (v is JSONObject) jsonObjectToMap(v) else v
                }
                JSONObject.NULL -> "null"
                else -> value
            }
        }
        return map
    }

    private fun describeToolCall(toolName: String, input: Map<String, Any>): String {
        return when (toolName) {
            "send_sms" -> "Sending SMS to ${input["contact_name"] ?: input["phone_number"]}..."
            "make_phone_call" -> "Calling ${input["contact_name"] ?: input["phone_number"]}..."
            "open_app" -> "Opening ${input["app_name"]}..."
            "list_apps" -> "Listing installed apps..."
            "browse_web" -> if (input.containsKey("search_query")) "Searching: ${input["search_query"]}..." else "Opening ${input["url"]}..."
            "toggle_setting" -> "${if (input["enable"] == true) "Enabling" else "Disabling"} ${input["setting"]}..."
            "send_whatsapp" -> {
                val contact = input["contact_name"] ?: input["group_name"]
                val isGroup = input["group_name"] != null
                val hasFile = input["file_path"] != null || input["file_name"] != null
                val target = if (isGroup) "group $contact" else "$contact"
                if (hasFile) "Sending file to $target via WhatsApp..." else "Sending WhatsApp to $target..."
            }
            "send_email" -> "Composing email to ${input["to"]}..."
            "create_calendar_event" -> "Creating event: ${input["title"]}..."
            "set_reminder" -> "Setting reminder: ${input["message"]}..."
            "set_alarm" -> "Setting ${input["action"] ?: "alarm"}..."
            "get_contacts" -> "Looking up contacts: ${input["name_query"]}..."
            "file_manager" -> "File: ${input["action"]} ${input["query"] ?: input["path"] ?: ""}..."
            "media_control" -> "Media: ${input["action"]}..."
            "brightness_control" -> "Brightness: ${input["action"]}..."
            "clipboard" -> "Clipboard: ${input["action"]}..."
            "share_content" -> "Sharing content..."
            "take_screenshot" -> "Taking screenshot..."
            "device_info" -> "Getting ${input["query"] ?: "device"} info..."
            "notifications" -> "Notifications: ${input["action"]}..."
            "auto_scroll_feed" -> {
                val app = input["app"] ?: "feed"
                val count = input["count"] ?: ""
                when (input["action"]?.toString()) {
                    "scroll", "start" -> "Scrolling $app ${count}x..."
                    "stop" -> "Stopping auto-scroll..."
                    "next" -> "Next item..."
                    "like" -> "Liking current item..."
                    else -> "Feed: ${input["action"]}..."
                }
            }
            "control_app_ui" -> "Controlling ${input["app_package"]}..."
            "screen_observe" -> {
                val pkg = input["app_package"]?.toString()
                if (pkg.isNullOrBlank()) "Observing screen..." else "Observing $pkg..."
            }
            "navigate_guide" -> {
                val act = input["action"]?.toString()
                if (act == "dismiss" || act == "hide") "Clearing highlight..."
                else "Highlighting \"${input["hint"] ?: "next step"}\"..."
            }
            "think" -> {
                val thought = input["thought"]?.toString()?.lineSequence()?.firstOrNull()?.take(80)
                if (thought.isNullOrBlank()) "Thinking..." else "Thinking: $thought"
            }
            "web_search" -> "Searching: ${input["query"]}..."
            "web_fetch" -> "Reading ${input["url"]}..."
            "memory" -> "Memory: ${input["action"]} ${input["key"] ?: input["query"] ?: ""}..."
            "read_sms" -> "Reading messages${input["contact_name"]?.let { " from $it" } ?: ""}..."
            "call_log" -> "Checking call history${input["contact_name"]?.let { " for $it" } ?: ""}..."
            "get_location" -> "Getting current location..."
            "notes" -> "Notes: ${input["action"]} ${input["title"] ?: input["query"] ?: ""}..."
            "screen_time" -> "Checking screen time (${input["action"] ?: "today"})..."
            "skills" -> "Skills: ${input["action"]}..."
            "schedule" -> "Schedule: ${input["action"]} ${input["name"] ?: ""}..."
            "github" -> {
                val act = input["action"]
                val repo = input["repo"]?.let { " $it" } ?: ""
                val num = input["number"]?.let { " #$it" } ?: ""
                "GitHub: $act$repo$num..."
            }
            "text_to_speech" -> when (input["action"]?.toString()) {
                "speak" -> "Speaking aloud..."
                "stop" -> "Stopping speech..."
                else -> "TTS: ${input["action"]}..."
            }
            else -> "Executing $toolName..."
        }
    }
}

package com.androclaw.api

import android.content.SharedPreferences
import com.androclaw.api.models.ClaudeRequest
import com.androclaw.api.models.ClaudeResponse
import com.androclaw.api.models.ContentBlock
import com.androclaw.api.models.Message
import com.androclaw.tools.ToolExecutor
import com.androclaw.utils.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ToolStatus(val toolName: String, val description: String, val isComplete: Boolean = false)

sealed class AgentEvent {
    data class ToolExecuting(val status: ToolStatus) : AgentEvent()
    data class ToolCompleted(val status: ToolStatus) : AgentEvent()
    data class FinalResponse(val text: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

@Singleton
class ClaudeRepository @Inject constructor(
    private val apiService: ClaudeApiService,
    private val toolExecutor: ToolExecutor,
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    @Named("regular") private val prefs: SharedPreferences
) {
    private val _agentEvents = MutableStateFlow<AgentEvent?>(null)
    val agentEvents: StateFlow<AgentEvent?> = _agentEvents

    private fun getApiKey(): String? =
        encryptedPrefs.getString(Constants.PREF_API_KEY, null)

    private fun getModel(): String {
        val saved = prefs.getString(Constants.PREF_MODEL, Constants.DEFAULT_MODEL) ?: Constants.DEFAULT_MODEL
        // Validate against known models — stale prefs from old versions may have invalid IDs
        return if (saved in Constants.MODEL_OPTIONS.keys) saved else Constants.DEFAULT_MODEL
    }

    private fun getEnabledTools(): Set<String> =
        prefs.getStringSet(Constants.PREF_ENABLED_TOOLS, Constants.ALL_TOOL_NAMES.toSet())
            ?: Constants.ALL_TOOL_NAMES.toSet()

    suspend fun sendMessage(
        conversationHistory: MutableList<Message>,
        userText: String
    ): Result<String> {
        val apiKey = getApiKey()
            ?: return Result.failure(Exception("API key not set. Please go to Settings to add your Claude API key."))

        // Add user message
        conversationHistory.add(Message(role = "user", content = userText))

        val tools = ToolDefinitions.getAllTools(getEnabledTools())
        var attempts = 0
        val maxLoopIterations = 10 // Safety limit for tool use loops

        while (attempts < maxLoopIterations) {
            val request = ClaudeRequest(
                model = getModel(),
                maxTokens = Constants.MAX_TOKENS,
                system = Constants.SYSTEM_PROMPT,
                tools = tools.ifEmpty { null },
                messages = conversationHistory
            )

            val response = callApiWithRetry(apiKey, request)
                ?: return Result.failure(Exception("Failed to get response from Claude after retries."))

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

            if (toolUseBlocks.isEmpty() || response.stopReason == "end_turn") {
                // No more tool calls — extract final text
                val finalText = response.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text ?: "" }
                    .trim()

                _agentEvents.value = AgentEvent.FinalResponse(finalText)
                return Result.success(finalText)
            }

            // Execute each tool and collect results
            val toolResults = mutableListOf<Map<String, Any>>()

            for (block in toolUseBlocks) {
                val toolName = block.name ?: continue
                val toolId = block.id ?: continue
                val toolInput = block.input ?: emptyMap()

                val description = describeToolCall(toolName, toolInput)
                _agentEvents.value = AgentEvent.ToolExecuting(
                    ToolStatus(toolName, description)
                )

                val result = try {
                    toolExecutor.execute(toolName, toolInput)
                } catch (e: Exception) {
                    "Error executing $toolName: ${e.message}"
                }

                _agentEvents.value = AgentEvent.ToolCompleted(
                    ToolStatus(toolName, description, isComplete = true)
                )

                toolResults.add(
                    mapOf(
                        "type" to "tool_result",
                        "tool_use_id" to toolId,
                        "content" to result
                    )
                )
            }

            // Add tool results as user message
            conversationHistory.add(Message(role = "user", content = toolResults))
            attempts++
        }

        return Result.failure(Exception("Agent exceeded maximum tool use iterations."))
    }

    private suspend fun callApiWithRetry(
        apiKey: String,
        request: ClaudeRequest,
        maxRetries: Int = 3
    ): ClaudeResponse? {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val response = apiService.sendMessage(apiKey = apiKey, request = request)
                if (response.isSuccessful) {
                    return response.body()
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    lastError = Exception("API error ${response.code()}: $errorBody")
                    if (response.code() in listOf(429, 500, 502, 503)) {
                        delay(1000L * (attempt + 1)) // Exponential-ish backoff
                    } else {
                        // Non-retryable error
                        _agentEvents.value = AgentEvent.Error(lastError!!.message ?: "API error")
                        return null
                    }
                }
            } catch (e: Exception) {
                lastError = e
                delay(1000L * (attempt + 1))
            }
        }
        _agentEvents.value = AgentEvent.Error(lastError?.message ?: "Unknown error")
        return null
    }

    private fun describeToolCall(toolName: String, input: Map<String, Any>): String {
        return when (toolName) {
            "send_sms" -> "Sending SMS to ${input["contact_name"] ?: input["phone_number"]}..."
            "make_phone_call" -> "Calling ${input["contact_name"] ?: input["phone_number"]}..."
            "open_app" -> "Opening ${input["app_name"]}..."
            "list_apps" -> "Listing installed apps..."
            "browse_web" -> if (input.containsKey("search_query")) "Searching: ${input["search_query"]}..." else "Opening ${input["url"]}..."
            "toggle_setting" -> "${if (input["enable"] == true) "Enabling" else "Disabling"} ${input["setting"]}..."
            "send_whatsapp" -> "Sending WhatsApp to ${input["contact_name"]}..."
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
            else -> "Executing $toolName..."
        }
    }
}

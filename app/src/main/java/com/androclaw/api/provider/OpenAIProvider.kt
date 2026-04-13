package com.androclaw.api.provider

import com.androclaw.api.models.ContentBlock
import com.androclaw.api.models.Message
import com.androclaw.api.models.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible provider. Works with OpenAI API, as well as any
 * OpenAI-compatible endpoint (Groq, Together, local vLLM, etc.)
 * by changing the baseUrl.
 */
class OpenAIProvider(
    private val okHttpClient: OkHttpClient,
    override val id: String = "openai",
    override val displayName: String = "OpenAI",
    private val baseUrl: String = "https://api.openai.com/v1",
    /** When set (e.g. Ollama), called on each request so Settings URL changes apply without restart. */
    private val dynamicBaseUrl: (() -> String)? = null,
    override val supportedModels: List<ModelInfo> = defaultModels
) : LlmProvider {

    override val supportsTools = true
    override val supportsStreaming = true
    override val supportsVision = true

    companion object {
        val defaultModels = listOf(
            ModelInfo("gpt-4o", "GPT-4o", 128000, supportsVision = true),
            ModelInfo("gpt-4o-mini", "GPT-4o Mini", 128000, supportsVision = true),
            ModelInfo("gpt-4-turbo", "GPT-4 Turbo", 128000, supportsVision = true),
            ModelInfo("gpt-3.5-turbo", "GPT-3.5 Turbo", 16385)
        )
    }

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int
    ): Result<LlmResponse> {
        return try {
            val body = buildRequestBody(model, systemPrompt, messages, tools, maxTokens, stream = false)
            val response = okHttpClient.newCall(newChatCompletionRequest(apiKey, body)).execute()
            val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            Result.success(parseResponse(json, model))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun streamMessage(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int
    ): Flow<StreamChunk> = flow {
        val body = buildRequestBody(model, systemPrompt, messages, tools, maxTokens, stream = true)
        val response = okHttpClient.newCall(newChatCompletionRequest(apiKey, body)).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            emit(StreamChunk.Error("API error ${response.code}: $errorBody"))
            return@flow
        }

        val reader = response.body?.byteStream()?.bufferedReader()
            ?: run { emit(StreamChunk.Error("Empty stream")); return@flow }

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    emit(StreamChunk.Done(null))
                    break
                }
                if (data.isEmpty()) continue

                try {
                    val event = JSONObject(data)
                    val choices = event.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue
                    val finishReason = choice.optString("finish_reason", "")

                    // Text content (and reasoning: Ollama / some OpenAI-compat models stream
                    // thinking in `reasoning` or `reasoning_content` with empty `content`.)
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        emit(StreamChunk.TextDelta(content))
                    }
                    val reasoning = delta.optString("reasoning", "")
                        .ifEmpty { delta.optString("reasoning_content", "") }
                    if (reasoning.isNotEmpty()) {
                        emit(StreamChunk.TextDelta(reasoning))
                    }

                    // Tool calls
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val tcId = tc.optString("id", "")
                            val function = tc.optJSONObject("function")
                            if (tcId.isNotEmpty() && function != null) {
                                val name = function.optString("name", "")
                                if (name.isNotEmpty()) {
                                    emit(StreamChunk.ToolUseStart(tcId, name))
                                }
                            }
                            val args = function?.optString("arguments", "") ?: ""
                            if (args.isNotEmpty()) {
                                emit(StreamChunk.ToolInputDelta(args))
                            }
                        }
                    }

                    if (finishReason.isNotEmpty() && finishReason != "null") {
                        // OpenAI-style streams end tool calls with finish_reason only on a later chunk; finalize
                        // the assembled function call so the repository can execute tools.
                        if (finishReason == "tool_calls") {
                            emit(StreamChunk.ToolUseEnd(""))
                        }
                        emit(StreamChunk.Done(finishReason))
                    }
                } catch (_: Exception) {}
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun resolvedBaseUrl(): String {
        val raw = dynamicBaseUrl?.invoke() ?: baseUrl
        return raw.trim().trimEnd('/')
    }

    private fun newChatCompletionRequest(apiKey: String, body: JSONObject): Request {
        val b = Request.Builder()
            .url("${resolvedBaseUrl()}/chat/completions")
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            b.header("Authorization", "Bearer $apiKey")
        }
        return b.post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    private fun buildRequestBody(
        model: String,
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", maxTokens)
        if (stream) body.put("stream", true)

        val messagesArray = JSONArray()

        // System message
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Convert messages from Claude format to OpenAI format
        for (msg in messages) {
            val role = convertRole(msg.role)
            when (val content = msg.content) {
                is String -> {
                    messagesArray.put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                is List<*> -> {
                    val parts = content.filterIsInstance<Map<*, *>>()
                    if (parts.any { it["type"] == "tool_result" }) {
                        for (part in parts) {
                            if (part["type"] == "tool_result") {
                                val toolMsg = JSONObject()
                                toolMsg.put("role", "tool")
                                toolMsg.put("tool_call_id", part["tool_use_id"]?.toString() ?: "")
                                toolMsg.put("content", toolResultContentToOpenAiString(part["content"]))
                                messagesArray.put(toolMsg)
                            }
                        }
                        continue
                    }
                    // Assistant turns with tool_use must use OpenAI "tool_calls", not Claude blocks in content.
                    if (role == "assistant" && parts.any { it["type"] == "tool_use" }) {
                        messagesArray.put(assistantToolCallsToOpenAiMessage(parts))
                        continue
                    }
                    // Assistant text-only as a list of blocks (rare)
                    if (role == "assistant") {
                        val text = parts.filter { it["type"] == "text" }
                            .joinToString("\n") { it["text"]?.toString() ?: "" }
                            .trim()
                        messagesArray.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", text)
                        })
                        continue
                    }
                    // User multimodal: Claude image blocks -> OpenAI image_url parts
                    if (role == "user") {
                        val openAiContent = claudeUserPartsToOpenAiContent(parts)
                        messagesArray.put(JSONObject().apply {
                            put("role", "user")
                            put("content", openAiContent)
                        })
                        continue
                    }
                    messagesArray.put(JSONObject().apply {
                        put("role", role)
                        put("content", content.toString())
                    })
                }
                else -> {
                    messagesArray.put(JSONObject().apply {
                        put("role", role)
                        put("content", content.toString())
                    })
                }
            }
        }
        body.put("messages", messagesArray)

        // Tools → OpenAI function calling format
        if (!tools.isNullOrEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("type", "function")
                val funcObj = JSONObject()
                funcObj.put("name", tool.name)
                funcObj.put("description", tool.description)
                val params = JSONObject()
                params.put("type", "object")
                val props = JSONObject()
                for ((key, prop) in tool.inputSchema.properties) {
                    val propObj = JSONObject()
                    propObj.put("type", prop.type)
                    prop.description?.let { propObj.put("description", it) }
                    prop.enum?.let { propObj.put("enum", JSONArray(it)) }
                    props.put(key, propObj)
                }
                params.put("properties", props)
                if (tool.inputSchema.required.isNotEmpty()) {
                    params.put("required", JSONArray(tool.inputSchema.required))
                }
                funcObj.put("parameters", params)
                toolObj.put("function", funcObj)
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    private fun convertRole(role: String): String = when (role) {
        "user" -> "user"
        "assistant" -> "assistant"
        "tool" -> "tool"
        else -> role
    }

    private fun parseResponse(json: JSONObject, model: String): LlmResponse {
        val choices = json.optJSONArray("choices") ?: return LlmResponse(emptyList(), null, model)
        if (choices.length() == 0) return LlmResponse(emptyList(), null, model)

        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: return LlmResponse(emptyList(), null, model)
        val finishReason = choice.optString("finish_reason", null)

        val contentBlocks = mutableListOf<ContentBlock>()

        // Text content (+ optional reasoning block from compat APIs)
        val reasoning = message.optString("reasoning", "")
            .ifEmpty { message.optString("reasoning_content", "") }
        val content = message.optString("content", "")
        val textCombined = buildString {
            if (reasoning.isNotEmpty()) append(reasoning.trimEnd())
            if (content.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(content)
            }
        }
        if (textCombined.isNotEmpty()) {
            contentBlocks.add(ContentBlock(type = "text", text = textCombined))
        }

        // Tool calls
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val function = tc.optJSONObject("function") ?: continue
                val args = try {
                    jsonObjectToMap(JSONObject(function.optString("arguments", "{}")))
                } catch (_: Exception) { emptyMap() }

                contentBlocks.add(ContentBlock(
                    type = "tool_use",
                    id = tc.optString("id"),
                    name = function.optString("name"),
                    input = args
                ))
            }
        }

        return LlmResponse(
            content = contentBlocks,
            stopReason = if (finishReason == "tool_calls") "tool_use" else finishReason,
            model = model
        )
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.get(it)) }
                JSONObject.NULL -> "null"
                else -> value
            }
        }
        return map
    }

    private fun jsonValueToKotlin(value: Any): Any = when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.get(it)) }
        JSONObject.NULL -> "null"
        else -> value
    }

    /**
     * Ollama/OpenAI reject Claude-style assistant content (list of tool_use maps).
     * Emit proper `tool_calls` + string `content`.
     */
    private fun assistantToolCallsToOpenAiMessage(parts: List<Map<*, *>>): JSONObject {
        val text = parts.filter { it["type"] == "text" }
            .joinToString("\n") { it["text"]?.toString() ?: "" }
            .trim()
        val toolCalls = JSONArray()
        for (p in parts.filter { it["type"] == "tool_use" }) {
            val id = p["id"]?.toString() ?: ""
            val name = p["name"]?.toString() ?: ""
            val argsJson = toolInputToArgumentsJson(p["input"])
            toolCalls.put(
                JSONObject().apply {
                    put("id", id)
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", name)
                            put("arguments", argsJson)
                        }
                    )
                }
            )
        }
        return JSONObject().apply {
            put("role", "assistant")
            put("content", if (text.isNotEmpty()) text else "")
            put("tool_calls", toolCalls)
        }
    }

    private fun toolInputToArgumentsJson(input: Any?): String {
        return when (input) {
            null -> "{}"
            is String -> input.ifBlank { "{}" }.let { s ->
                try {
                    JSONObject(s)
                    s
                } catch (_: Exception) {
                    JSONObject().apply { put("value", s) }.toString()
                }
            }
            is Map<*, *> -> mapToJsonObject(input).toString()
            else -> JSONObject.quote(input.toString())
        }
    }

    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            val key = k?.toString() ?: continue
            when (v) {
                null -> o.put(key, JSONObject.NULL)
                is Map<*, *> -> o.put(key, mapToJsonObject(v))
                is List<*> -> o.put(key, listToJsonArray(v))
                is Boolean -> o.put(key, v)
                is Int -> o.put(key, v)
                is Long -> o.put(key, v)
                is Double -> o.put(key, v)
                is Float -> o.put(key, v.toDouble())
                is Number -> o.put(key, v)
                else -> o.put(key, v.toString())
            }
        }
        return o
    }

    private fun listToJsonArray(list: List<*>): JSONArray {
        val a = JSONArray()
        for (v in list) {
            when (v) {
                null -> a.put(JSONObject.NULL)
                is Map<*, *> -> a.put(mapToJsonObject(v))
                is List<*> -> a.put(listToJsonArray(v))
                is Boolean -> a.put(v)
                is Int -> a.put(v)
                is Long -> a.put(v)
                is Double -> a.put(v)
                is Float -> a.put(v.toDouble())
                is Number -> a.put(v)
                else -> a.put(v.toString())
            }
        }
        return a
    }

    /** Tool role messages must be a string; multimodal tool_result lists become text + placeholder. */
    private fun toolResultContentToOpenAiString(raw: Any?): String {
        if (raw == null) return ""
        when (raw) {
            is String -> return raw
            is List<*> -> {
                val sb = StringBuilder()
                for (item in raw.filterIsInstance<Map<*, *>>()) {
                    when (item["type"]?.toString()) {
                        "text" -> sb.append(item["text"]?.toString() ?: "").append('\n')
                        "image" -> sb.append("[Screenshot image in tool result — use the text below if present.]\n")
                        else -> sb.append(item.toString()).append('\n')
                    }
                }
                return sb.toString().trim().ifEmpty { "(empty tool result)" }
            }
            else -> return raw.toString()
        }
    }

    /** Claude-style user content blocks -> OpenAI chat vision parts. */
    private fun claudeUserPartsToOpenAiContent(parts: List<Map<*, *>>): Any {
        if (parts.isEmpty()) return ""
        val out = JSONArray()
        for (p in parts) {
            when (p["type"]?.toString()) {
                "text" -> {
                    val t = p["text"]?.toString() ?: ""
                    if (t.isNotEmpty()) {
                        out.put(
                            JSONObject().apply {
                                put("type", "text")
                                put("text", t)
                            }
                        )
                    }
                }
                "image" -> {
                    val src = p["source"] as? Map<*, *> ?: continue
                    val media = src["media_type"]?.toString() ?: "image/jpeg"
                    val data = src["data"]?.toString() ?: ""
                    if (data.isNotEmpty()) {
                        out.put(
                            JSONObject().apply {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    JSONObject().apply {
                                        put("url", "data:$media;base64,$data")
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        return if (out.length() == 0) "" else out
    }
}

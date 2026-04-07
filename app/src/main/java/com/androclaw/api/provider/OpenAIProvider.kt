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
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
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
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()

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

                    // Text content
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        emit(StreamChunk.TextDelta(content))
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
                        emit(StreamChunk.Done(finishReason))
                    }
                } catch (_: Exception) {}
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

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
            val msgObj = JSONObject()
            msgObj.put("role", convertRole(msg.role))

            when (val content = msg.content) {
                is String -> msgObj.put("content", content)
                is List<*> -> {
                    // Handle tool results and multi-part content
                    val parts = content.filterIsInstance<Map<*, *>>()
                    if (parts.any { it["type"] == "tool_result" }) {
                        // OpenAI uses separate "tool" role messages for each tool result
                        for (part in parts) {
                            if (part["type"] == "tool_result") {
                                val toolMsg = JSONObject()
                                toolMsg.put("role", "tool")
                                toolMsg.put("tool_call_id", part["tool_use_id"] ?: "")
                                toolMsg.put("content", part["content"]?.toString() ?: "")
                                messagesArray.put(toolMsg)
                            }
                        }
                        continue // Don't add the wrapper message
                    } else {
                        // Multi-part content (text + images)
                        val contentArray = JSONArray()
                        for (part in parts) {
                            contentArray.put(JSONObject(part))
                        }
                        msgObj.put("content", contentArray)
                    }
                }
                else -> msgObj.put("content", content.toString())
            }
            messagesArray.put(msgObj)
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

        // Text content
        val content = message.optString("content", "")
        if (content.isNotEmpty()) {
            contentBlocks.add(ContentBlock(type = "text", text = content))
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
}

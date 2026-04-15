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
 * Google Gemini provider via the Generative Language API.
 */
class GeminiProvider(private val okHttpClient: OkHttpClient) : LlmProvider {

    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val supportsTools = true
    override val supportsStreaming = true
    override val supportsVision = true

    override val supportedModels = listOf(
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", 1000000, supportsVision = true),
        ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", 1000000, supportsVision = true),
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", 1000000, supportsVision = true)
    )

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int
    ): Result<LlmResponse> {
        return try {
            val body = buildRequestBody(systemPrompt, messages, tools, maxTokens)
            val request = Request.Builder()
                .url("$baseUrl/models/$model:generateContent?key=$apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("Gemini API error ${response.code}: $responseBody"))
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
        val body = buildRequestBody(systemPrompt, messages, tools, maxTokens)
        val request = Request.Builder()
            .url("$baseUrl/models/$model:streamGenerateContent?key=$apiKey&alt=sse")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            emit(StreamChunk.Error("Gemini API error ${response.code}: $errorBody"))
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
                if (data.isEmpty()) continue

                try {
                    val event = JSONObject(data)
                    val candidates = event.optJSONArray("candidates") ?: continue
                    if (candidates.length() == 0) continue

                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue

                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            emit(StreamChunk.TextDelta(text))
                        }

                        val functionCall = part.optJSONObject("functionCall")
                        if (functionCall != null) {
                            val name = functionCall.optString("name", "")
                            val args = functionCall.optJSONObject("args")?.toString() ?: "{}"
                            val callId = "gemini_${System.currentTimeMillis()}"
                            emit(StreamChunk.ToolUseStart(callId, name))
                            emit(StreamChunk.ToolInputDelta(args))
                            emit(StreamChunk.ToolUseEnd(callId))
                        }
                    }

                    val finishReason = candidate.optString("finishReason", "")
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
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int
    ): JSONObject {
        val body = JSONObject()

        // System instruction
        if (systemPrompt != null) {
            body.put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
        }

        // Generation config
        body.put("generationConfig", JSONObject().apply {
            put("maxOutputTokens", maxTokens)
        })

        // Convert messages to Gemini format
        val contents = JSONArray()
        for (msg in messages) {
            val role = if (msg.role == "assistant") "model" else "user"
            val content = msg.content

            if (content is String) {
                contents.put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", content)))
                })
            } else if (content is List<*>) {
                val parts = content.filterIsInstance<Map<*, *>>()
                // Skip tool result messages for now (Gemini uses functionResponse)
                if (parts.any { it["type"] == "tool_result" }) {
                    val geminiParts = JSONArray()
                    for (part in parts) {
                        if (part["type"] == "tool_result") {
                            geminiParts.put(JSONObject().apply {
                                put("functionResponse", JSONObject().apply {
                                    put("name", "tool_response")
                                    put("response", JSONObject().put("result", part["content"]?.toString() ?: ""))
                                })
                            })
                        }
                    }
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", geminiParts)
                    })
                } else {
                    val geminiParts = JSONArray()
                    for (part in parts) {
                        when (part["type"]) {
                            "text" -> geminiParts.put(JSONObject().put("text", part["text"] ?: ""))
                            "tool_use" -> {
                                geminiParts.put(JSONObject().apply {
                                    put("functionCall", JSONObject().apply {
                                        put("name", part["name"] ?: "")
                                        put("args", JSONObject(part["input"] as? Map<*, *> ?: emptyMap<String, Any>()))
                                    })
                                })
                            }
                        }
                    }
                    contents.put(JSONObject().apply {
                        put("role", role)
                        put("parts", geminiParts)
                    })
                }
            }
        }
        body.put("contents", contents)

        // Tools
        if (!tools.isNullOrEmpty()) {
            val functionDeclarations = JSONArray()
            for (tool in tools) {
                val funcObj = JSONObject()
                funcObj.put("name", tool.name)
                funcObj.put("description", tool.description)
                val params = JSONObject()
                params.put("type", "OBJECT")
                val props = JSONObject()
                for ((key, prop) in tool.inputSchema.properties) {
                    val propObj = JSONObject()
                    propObj.put("type", prop.type.uppercase())
                    prop.description?.let { propObj.put("description", it) }
                    prop.enum?.let { propObj.put("enum", JSONArray(it)) }
                    props.put(key, propObj)
                }
                params.put("properties", props)
                if (tool.inputSchema.required.isNotEmpty()) {
                    params.put("required", JSONArray(tool.inputSchema.required))
                }
                funcObj.put("parameters", params)
                functionDeclarations.put(funcObj)
            }
            body.put("tools", JSONArray().put(JSONObject().put("function_declarations", functionDeclarations)))
        }

        return body
    }

    private fun parseResponse(json: JSONObject, model: String): LlmResponse {
        val candidates = json.optJSONArray("candidates") ?: return LlmResponse(emptyList(), null, model)
        if (candidates.length() == 0) return LlmResponse(emptyList(), null, model)

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content") ?: return LlmResponse(emptyList(), null, model)
        val parts = content.optJSONArray("parts") ?: return LlmResponse(emptyList(), null, model)
        val finishReason = candidate.optString("finishReason", "").takeIf { it.isNotEmpty() }

        val contentBlocks = mutableListOf<ContentBlock>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
                contentBlocks.add(ContentBlock(type = "text", text = text))
            }
            val functionCall = part.optJSONObject("functionCall")
            if (functionCall != null) {
                val name = functionCall.optString("name", "")
                val args = functionCall.optJSONObject("args")
                contentBlocks.add(ContentBlock(
                    type = "tool_use",
                    id = "gemini_${System.currentTimeMillis()}_$i",
                    name = name,
                    input = if (args != null) jsonObjectToMap(args) else null
                ))
            }
        }

        return LlmResponse(content = contentBlocks, stopReason = finishReason, model = model)
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> (0 until value.length()).map {
                    val v = value.get(it)
                    if (v is JSONObject) jsonObjectToMap(v) else v
                }
                JSONObject.NULL -> "null"
                else -> value
            }
        }
        return map
    }
}

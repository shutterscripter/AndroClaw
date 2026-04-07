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
import java.io.BufferedReader

class ClaudeProvider(private val okHttpClient: OkHttpClient) : LlmProvider {

    override val id = "claude"
    override val displayName = "Anthropic Claude"
    override val supportsTools = true
    override val supportsStreaming = true
    override val supportsVision = true

    override val supportedModels = listOf(
        ModelInfo("claude-opus-4-6", "Claude Opus 4.6", 200000, supportsVision = true),
        ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", 200000, supportsVision = true),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", 200000, supportsVision = true)
    )

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
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            Result.success(parseResponse(json))
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
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
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
            parseSSEStream(reader).collect { emit(it) }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseSSEStream(reader: BufferedReader): Flow<StreamChunk> = flow {
        var currentToolId: String? = null
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (!l.startsWith("data: ")) continue
            val data = l.removePrefix("data: ").trim()
            if (data == "[DONE]" || data.isEmpty()) continue

            try {
                val event = JSONObject(data)
                val type = event.optString("type", "")

                when (type) {
                    "content_block_start" -> {
                        val block = event.optJSONObject("content_block") ?: continue
                        when (block.optString("type")) {
                            "tool_use" -> {
                                val id = block.optString("id", "")
                                val name = block.optString("name", "")
                                currentToolId = id
                                emit(StreamChunk.ToolUseStart(id, name))
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) emit(StreamChunk.TextDelta(text))
                            }
                            "input_json_delta" -> {
                                val json = delta.optString("partial_json", "")
                                if (json.isNotEmpty()) emit(StreamChunk.ToolInputDelta(json))
                            }
                        }
                    }
                    "content_block_stop" -> {
                        currentToolId?.let {
                            emit(StreamChunk.ToolUseEnd(it))
                            currentToolId = null
                        }
                    }
                    "message_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        val stopReason = delta.optString("stop_reason", "")
                        if (stopReason.isNotEmpty()) {
                            emit(StreamChunk.Done(stopReason))
                        }
                    }
                    "message_stop" -> {
                        emit(StreamChunk.Done(null))
                    }
                    "error" -> {
                        val error = event.optJSONObject("error")
                        emit(StreamChunk.Error(error?.optString("message", "Stream error") ?: "Stream error"))
                    }
                }
            } catch (_: Exception) { /* skip malformed events */ }
        }
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
        if (systemPrompt != null) body.put("system", systemPrompt)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            when (val content = msg.content) {
                is String -> msgObj.put("content", content)
                is List<*> -> msgObj.put("content", JSONArray(content.map { item ->
                    when (item) {
                        is Map<*, *> -> JSONObject(item)
                        else -> item
                    }
                }))
                else -> msgObj.put("content", content.toString())
            }
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        if (!tools.isNullOrEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("name", tool.name)
                toolObj.put("description", tool.description)
                val schema = JSONObject()
                schema.put("type", "object")
                val props = JSONObject()
                for ((key, prop) in tool.inputSchema.properties) {
                    val propObj = JSONObject()
                    propObj.put("type", prop.type)
                    prop.description?.let { propObj.put("description", it) }
                    prop.enum?.let { propObj.put("enum", JSONArray(it)) }
                    if (prop.items != null) {
                        val itemsObj = JSONObject()
                        itemsObj.put("type", prop.items.type)
                        prop.items.description?.let { itemsObj.put("description", it) }
                        propObj.put("items", itemsObj)
                    }
                    props.put(key, propObj)
                }
                schema.put("properties", props)
                if (tool.inputSchema.required.isNotEmpty()) {
                    schema.put("required", JSONArray(tool.inputSchema.required))
                }
                toolObj.put("input_schema", schema)
                toolsArray.put(toolObj)
            }
            body.put("tools", toolsArray)
        }

        return body
    }

    private fun parseResponse(json: JSONObject): LlmResponse {
        val contentArray = json.getJSONArray("content")
        val contentBlocks = mutableListOf<ContentBlock>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            val blockType = block.getString("type")
            contentBlocks.add(
                when (blockType) {
                    "text" -> ContentBlock(type = "text", text = block.optString("text", ""))
                    "tool_use" -> ContentBlock(
                        type = "tool_use",
                        id = block.optString("id"),
                        name = block.optString("name"),
                        input = jsonObjectToMap(block.optJSONObject("input"))
                    )
                    else -> ContentBlock(type = blockType)
                }
            )
        }

        return LlmResponse(
            content = contentBlocks,
            stopReason = json.optString("stop_reason", null),
            model = json.optString("model", "")
        )
    }

    private fun jsonObjectToMap(json: JSONObject?): Map<String, Any>? {
        if (json == null) return null
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            map[key] = jsonValueToKotlin(json.get(key))
        }
        return map
    }

    private fun jsonValueToKotlin(value: Any): Any = when (value) {
        is JSONObject -> jsonObjectToMap(value) ?: emptyMap<String, Any>()
        is JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.get(it)) }
        JSONObject.NULL -> "null"
        else -> value
    }
}

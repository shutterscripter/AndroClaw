package com.androclaw.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- Request models ---
// These use generated adapters since we control the shape

@JsonClass(generateAdapter = true)
data class ClaudeRequest(
    val model: String,
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
    val system: String? = null,
    val tools: List<ToolDefinition>? = null,
    val messages: List<Message>
)

@JsonClass(generateAdapter = true)
data class Message(
    val role: String,
    val content: Any // String or List<Map<String, Any>>
)

@JsonClass(generateAdapter = true)
data class ToolDefinition(
    val name: String,
    val description: String,
    @Json(name = "input_schema") val inputSchema: InputSchema
)

@JsonClass(generateAdapter = true)
data class InputSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema>,
    val required: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PropertySchema(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null,
    val items: PropertySchema? = null
)

// --- Response models (parsed manually to handle unknown fields) ---

data class ClaudeResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ContentBlock>,
    val model: String,
    val stopReason: String?
)

data class ContentBlock(
    val type: String, // "text" or "tool_use"
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any>? = null
)

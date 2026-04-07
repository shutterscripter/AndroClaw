package com.androclaw.api.provider

import com.androclaw.api.models.ContentBlock
import com.androclaw.api.models.Message
import com.androclaw.api.models.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Unified response from any LLM provider.
 */
data class LlmResponse(
    val content: List<ContentBlock>,
    val stopReason: String?,
    val model: String
)

/**
 * A single streaming chunk from the provider.
 */
sealed class StreamChunk {
    data class TextDelta(val text: String) : StreamChunk()
    data class ToolUseStart(val id: String, val name: String) : StreamChunk()
    data class ToolInputDelta(val json: String) : StreamChunk()
    data class ToolUseEnd(val id: String) : StreamChunk()
    data class Done(val stopReason: String?) : StreamChunk()
    data class Error(val message: String) : StreamChunk()
}

/**
 * Abstract LLM provider interface. Each provider (Claude, OpenAI, Gemini, etc.)
 * implements this to normalize their API into a common format.
 */
interface LlmProvider {
    val id: String
    val displayName: String
    val supportedModels: List<ModelInfo>
    val supportsTools: Boolean
    val supportsStreaming: Boolean
    val supportsVision: Boolean

    /**
     * Send a non-streaming request. Returns the complete response.
     */
    suspend fun sendMessage(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int
    ): Result<LlmResponse>

    /**
     * Send a streaming request. Returns a Flow of chunks.
     */
    fun streamMessage(
        apiKey: String,
        model: String,
        systemPrompt: String?,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        maxTokens: Int
    ): Flow<StreamChunk>
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextWindow: Int = 0,
    val supportsVision: Boolean = false
)

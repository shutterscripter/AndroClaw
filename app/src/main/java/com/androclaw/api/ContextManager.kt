package com.androclaw.api

import com.androclaw.api.models.Message
import com.androclaw.api.provider.LlmProvider
import com.androclaw.api.provider.ProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages context window usage — estimates token counts, triggers compaction
 * when conversation history approaches model limits, and prunes verbose tool results.
 *
 * Inspired by OpenClaw's context engine: token accounting, auto-compaction,
 * and pruning of old tool results to keep conversations within model limits.
 */
@Singleton
class ContextManager @Inject constructor(
    private val providerRegistry: ProviderRegistry
) {
    companion object {
        /** Average chars per token — conservative estimate across models */
        private const val CHARS_PER_TOKEN = 4.0

        /** Trigger compaction when usage exceeds this fraction of context window */
        private const val COMPACTION_THRESHOLD = 0.75

        /** After compaction, keep this many recent message pairs intact */
        private const val KEEP_RECENT_PAIRS = 4

        /** Max chars for a single tool result before it gets pruned in older messages */
        private const val TOOL_RESULT_PRUNE_CHARS = 2000

        /** Fallback context window if model info not found */
        private const val DEFAULT_CONTEXT_WINDOW = 128_000
    }

    /**
     * Estimate token count for a single string.
     */
    fun estimateTokens(text: String): Int {
        return (text.length / CHARS_PER_TOKEN).toInt()
    }

    /**
     * Estimate total token count for conversation messages + system prompt.
     */
    fun estimateConversationTokens(
        systemPrompt: String,
        messages: List<Message>,
        toolDefinitionsJson: String? = null
    ): Int {
        var total = estimateTokens(systemPrompt)

        // Tool schemas count toward context
        if (toolDefinitionsJson != null) {
            total += estimateTokens(toolDefinitionsJson)
        }

        for (msg in messages) {
            total += estimateMessageTokens(msg)
        }

        return total
    }

    /**
     * Estimate tokens for a single message, handling both String and List content.
     */
    private fun estimateMessageTokens(message: Message): Int {
        val content = message.content
        val text = when (content) {
            is String -> content
            is List<*> -> {
                // List of content blocks (tool use, tool results, etc.)
                content.joinToString("\n") { block ->
                    when (block) {
                        is Map<*, *> -> {
                            val type = block["type"]?.toString() ?: ""
                            when (type) {
                                "text" -> block["text"]?.toString() ?: ""
                                "tool_use" -> {
                                    val name = block["name"]?.toString() ?: ""
                                    val input = block["input"]?.toString() ?: ""
                                    "tool_use:$name $input"
                                }
                                "tool_result" -> {
                                    val resultContent = block["content"]
                                    when (resultContent) {
                                        is String -> resultContent
                                        is List<*> -> resultContent.joinToString("\n") {
                                            (it as? Map<*, *>)?.get("text")?.toString() ?: "[media]"
                                        }
                                        else -> resultContent?.toString() ?: ""
                                    }
                                }
                                else -> block.toString()
                            }
                        }
                        else -> block.toString()
                    }
                }
            }
            else -> content.toString()
        }
        // Add overhead for role/structure tokens
        return estimateTokens(text) + 4
    }

    /**
     * Get the context window size for the current model.
     */
    fun getContextWindow(providerId: String, modelId: String): Int {
        val provider = providerRegistry.getProvider(providerId)
        val modelInfo = provider?.supportedModels?.find { it.id == modelId }
        return modelInfo?.contextWindow ?: DEFAULT_CONTEXT_WINDOW
    }

    /**
     * Check if compaction is needed based on current token usage vs context window.
     */
    fun needsCompaction(
        systemPrompt: String,
        messages: List<Message>,
        providerId: String,
        modelId: String
    ): Boolean {
        val used = estimateConversationTokens(systemPrompt, messages)
        val limit = getContextWindow(providerId, modelId)
        return used > (limit * COMPACTION_THRESHOLD)
    }

    /**
     * Get current context usage as a fraction (0.0 to 1.0+).
     */
    fun getUsageFraction(
        systemPrompt: String,
        messages: List<Message>,
        providerId: String,
        modelId: String
    ): Float {
        val used = estimateConversationTokens(systemPrompt, messages)
        val limit = getContextWindow(providerId, modelId)
        return if (limit > 0) used.toFloat() / limit else 0f
    }

    /**
     * Get detailed context usage info.
     */
    fun getUsageInfo(
        systemPrompt: String,
        messages: List<Message>,
        providerId: String,
        modelId: String
    ): ContextUsageInfo {
        val usedTokens = estimateConversationTokens(systemPrompt, messages)
        val contextWindow = getContextWindow(providerId, modelId)
        return ContextUsageInfo(
            usedTokens = usedTokens,
            contextWindow = contextWindow,
            messageCount = messages.size,
            usageFraction = if (contextWindow > 0) usedTokens.toFloat() / contextWindow else 0f
        )
    }

    /**
     * Compact conversation history by:
     * 1. Pruning verbose tool results from older messages
     * 2. Building a summary of old messages to replace them
     *
     * Returns a compaction prompt that should be sent to the LLM to generate a summary,
     * plus the messages to keep intact.
     */
    fun prepareCompaction(
        messages: MutableList<Message>
    ): CompactionPlan? {
        if (messages.size < KEEP_RECENT_PAIRS * 2 + 2) {
            // Not enough messages to compact
            return null
        }

        // Split: old messages to summarize vs recent messages to keep
        val keepCount = KEEP_RECENT_PAIRS * 2 // pairs of user+assistant
        val keepFrom = messages.size - keepCount
        val oldMessages = messages.subList(0, keepFrom)
        val recentMessages = messages.subList(keepFrom, messages.size)

        // Build a text representation of old messages for summarization
        val oldConversationText = buildConversationText(oldMessages)

        return CompactionPlan(
            summaryPrompt = buildSummaryPrompt(oldConversationText),
            oldMessageCount = oldMessages.size,
            recentMessages = recentMessages.toList()
        )
    }

    /**
     * Apply compaction: replace conversation history with summary + recent messages.
     */
    fun applyCompaction(
        messages: MutableList<Message>,
        summary: String,
        plan: CompactionPlan
    ) {
        messages.clear()
        // Insert summary as a system-injected user/assistant pair
        messages.add(Message(
            role = "user",
            content = "[Earlier conversation summary requested]"
        ))
        messages.add(Message(
            role = "assistant",
            content = summary
        ))
        // Re-add recent messages
        messages.addAll(plan.recentMessages)
    }

    /**
     * Prune verbose tool results from older messages in-place.
     * Keeps recent messages intact, truncates old tool result content.
     */
    fun pruneOldToolResults(messages: MutableList<Message>) {
        if (messages.size <= KEEP_RECENT_PAIRS * 2) return

        val pruneUpTo = messages.size - (KEEP_RECENT_PAIRS * 2)

        for (i in 0 until pruneUpTo) {
            val msg = messages[i]
            val content = msg.content
            if (content is List<*>) {
                val pruned = content.map { block ->
                    if (block is Map<*, *> && block["type"] == "tool_result") {
                        val resultContent = block["content"]
                        if (resultContent is String && resultContent.length > TOOL_RESULT_PRUNE_CHARS) {
                            val truncated = resultContent.take(TOOL_RESULT_PRUNE_CHARS) +
                                "\n...[truncated ${resultContent.length - TOOL_RESULT_PRUNE_CHARS} chars]"
                            block.toMutableMap().apply { put("content", truncated) }
                        } else block
                    } else block
                }
                messages[i] = Message(role = msg.role, content = pruned)
            }
        }
    }

    private fun buildConversationText(messages: List<Message>): String {
        return messages.joinToString("\n\n") { msg ->
            val role = msg.role.uppercase()
            val text = when (val content = msg.content) {
                is String -> content
                is List<*> -> content.mapNotNull { block ->
                    when {
                        block is Map<*, *> && block["type"] == "text" -> block["text"]?.toString()
                        block is Map<*, *> && block["type"] == "tool_use" ->
                            "[Used tool: ${block["name"]}]"
                        block is Map<*, *> && block["type"] == "tool_result" -> {
                            val resultContent = block["content"]
                            val preview = when (resultContent) {
                                is String -> if (resultContent.length > 500)
                                    resultContent.take(500) + "..." else resultContent
                                else -> resultContent?.toString()?.take(500) ?: ""
                            }
                            "[Tool result: $preview]"
                        }
                        else -> null
                    }
                }.joinToString("\n")
                else -> content.toString()
            }
            "$role: $text"
        }
    }

    private fun buildSummaryPrompt(conversationText: String): String {
        return """Summarize the following conversation history concisely. Preserve:
- Key facts, decisions, and user preferences mentioned
- Important tool results and their outcomes
- Any ongoing tasks or context the user may refer back to

Keep the summary under 500 words. Focus on information needed to continue the conversation coherently.

CONVERSATION:
$conversationText

SUMMARY:"""
    }
}

data class ContextUsageInfo(
    val usedTokens: Int,
    val contextWindow: Int,
    val messageCount: Int,
    val usageFraction: Float
) {
    val usagePercent: Int get() = (usageFraction * 100).toInt().coerceIn(0, 100)
    val isNearLimit: Boolean get() = usageFraction > 0.75f
    val isOverLimit: Boolean get() = usageFraction > 0.95f
}

data class CompactionPlan(
    val summaryPrompt: String,
    val oldMessageCount: Int,
    val recentMessages: List<Message>
)

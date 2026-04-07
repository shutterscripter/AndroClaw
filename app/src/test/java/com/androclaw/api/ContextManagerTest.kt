package com.androclaw.api

import com.androclaw.api.models.Message
import com.androclaw.api.provider.LlmProvider
import com.androclaw.api.provider.ModelInfo
import com.androclaw.api.provider.ProviderRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ContextManagerTest {

    private lateinit var registry: ProviderRegistry
    private lateinit var manager: ContextManager

    @Before
    fun setUp() {
        registry = mock()
        val provider = mock<LlmProvider>()
        whenever(provider.supportedModels).thenReturn(
            listOf(ModelInfo(id = "fake-model", displayName = "Fake", contextWindow = 1000))
        )
        whenever(registry.getProvider("fake-provider")).thenReturn(provider)
        manager = ContextManager(registry)
    }

    @Test
    fun `estimateTokens uses the chars-per-token ratio`() {
        // 4 chars per token, so 8 chars → 2 tokens.
        assertThat(manager.estimateTokens("12345678")).isEqualTo(2)
        assertThat(manager.estimateTokens("")).isEqualTo(0)
    }

    @Test
    fun `estimateConversationTokens sums system + messages + tool schema overhead`() {
        val sys = "x".repeat(40)            // 10 tokens
        val toolSchema = "y".repeat(20)     // 5 tokens
        val msgs = listOf(
            Message(role = "user", content = "z".repeat(40))      // 10 + 4 overhead
        )
        val total = manager.estimateConversationTokens(sys, msgs, toolSchema)
        assertThat(total).isEqualTo(10 + 5 + 10 + 4)
    }

    @Test
    fun `estimateConversationTokens handles list-based content with tool blocks`() {
        val toolUseBlock = mapOf(
            "type" to "tool_use",
            "name" to "web_search",
            "input" to mapOf("query" to "kotlin")
        )
        val toolResultBlock = mapOf(
            "type" to "tool_result",
            "content" to "result text"
        )
        val msgs = listOf(
            Message(role = "assistant", content = listOf(toolUseBlock, toolResultBlock))
        )
        val total = manager.estimateConversationTokens("", msgs)
        // Should be > 0 even though content is a list, not a string.
        assertThat(total).isGreaterThan(0)
    }

    @Test
    fun `getContextWindow returns model context window when provider known`() {
        assertThat(manager.getContextWindow("fake-provider", "fake-model")).isEqualTo(1000)
    }

    @Test
    fun `getContextWindow falls back to default when model unknown`() {
        // Default is 128_000 from ContextManager.companion
        assertThat(manager.getContextWindow("fake-provider", "missing")).isEqualTo(128_000)
        assertThat(manager.getContextWindow("missing", "fake-model")).isEqualTo(128_000)
    }

    @Test
    fun `needsCompaction is true when usage exceeds 75 percent of window`() {
        // window = 1000 → threshold ≈ 750 tokens. 4000 chars ≈ 1000 tokens.
        val bigPrompt = "a".repeat(4000)
        assertThat(
            manager.needsCompaction(bigPrompt, emptyList(), "fake-provider", "fake-model")
        ).isTrue()
    }

    @Test
    fun `needsCompaction is false for small conversations`() {
        assertThat(
            manager.needsCompaction("hi", emptyList(), "fake-provider", "fake-model")
        ).isFalse()
    }

    @Test
    fun `getUsageInfo flag thresholds are sensible`() {
        val small = manager.getUsageInfo("hi", emptyList(), "fake-provider", "fake-model")
        assertThat(small.isNearLimit).isFalse()
        assertThat(small.isOverLimit).isFalse()
        assertThat(small.usagePercent).isAtLeast(0)

        val huge = manager.getUsageInfo(
            "a".repeat(5000), emptyList(), "fake-provider", "fake-model"
        )
        assertThat(huge.isNearLimit).isTrue()
        assertThat(huge.isOverLimit).isTrue()
        assertThat(huge.usagePercent).isEqualTo(100)
    }

    @Test
    fun `prepareCompaction returns null for short conversations`() {
        val msgs = mutableListOf(
            Message("user", "hi"),
            Message("assistant", "hello")
        )
        assertThat(manager.prepareCompaction(msgs)).isNull()
    }

    @Test
    fun `prepareCompaction keeps recent messages and summarizes older ones`() {
        // KEEP_RECENT_PAIRS = 4 → keep 8 messages; need >= 10 to compact.
        val msgs = MutableList(12) { i ->
            Message(role = if (i % 2 == 0) "user" else "assistant", content = "msg $i")
        }
        val plan = manager.prepareCompaction(msgs)
        assertThat(plan).isNotNull()
        assertThat(plan!!.recentMessages).hasSize(8)
        assertThat(plan.oldMessageCount).isEqualTo(4)
        assertThat(plan.summaryPrompt).contains("Summarize")
        assertThat(plan.summaryPrompt).contains("msg 0")
    }

    @Test
    fun `applyCompaction replaces history with summary plus recent`() {
        val msgs = MutableList(12) { i ->
            Message(role = if (i % 2 == 0) "user" else "assistant", content = "msg $i")
        }
        val plan = manager.prepareCompaction(msgs)!!
        manager.applyCompaction(msgs, summary = "earlier stuff", plan = plan)
        // 2 stub messages (user + assistant summary) + 8 recent
        assertThat(msgs).hasSize(2 + 8)
        assertThat(msgs[0].role).isEqualTo("user")
        assertThat(msgs[1].role).isEqualTo("assistant")
        assertThat(msgs[1].content).isEqualTo("earlier stuff")
        // Last message preserved
        assertThat(msgs.last().content).isEqualTo("msg 11")
    }

    @Test
    fun `pruneOldToolResults truncates large tool results in older messages`() {
        val bigResult = "x".repeat(5000)
        val toolResultBlock = mapOf("type" to "tool_result", "content" to bigResult)
        val msgs = MutableList<Message>(12) { i ->
            if (i == 0) {
                Message(role = "user", content = listOf(toolResultBlock))
            } else {
                Message(role = if (i % 2 == 0) "user" else "assistant", content = "msg $i")
            }
        }
        manager.pruneOldToolResults(msgs)

        @Suppress("UNCHECKED_CAST")
        val pruned = (msgs[0].content as List<Map<String, Any>>)[0]
        val prunedContent = pruned["content"] as String
        assertThat(prunedContent.length).isLessThan(bigResult.length)
        assertThat(prunedContent).contains("[truncated")
    }

    @Test
    fun `pruneOldToolResults leaves recent messages alone`() {
        val bigResult = "x".repeat(5000)
        val toolResultBlock = mapOf("type" to "tool_result", "content" to bigResult)
        // Only 6 messages — shorter than KEEP_RECENT_PAIRS*2 = 8 → nothing pruned.
        val msgs = MutableList<Message>(6) {
            Message(role = "assistant", content = listOf(toolResultBlock))
        }
        manager.pruneOldToolResults(msgs)

        @Suppress("UNCHECKED_CAST")
        val first = (msgs[0].content as List<Map<String, Any>>)[0]
        assertThat((first["content"] as String).length).isEqualTo(bigResult.length)
    }
}

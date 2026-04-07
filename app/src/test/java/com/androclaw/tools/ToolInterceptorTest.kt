package com.androclaw.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * ToolInterceptor unit tests.
 *
 * Note: tools that require the accessibility service (control_app_ui,
 * auto_scroll_feed, take_screenshot) are blocked here because
 * AndroClawAccessibilityService.instance is null in unit tests. We avoid those
 * paths and exercise rate-limiting / classification / audit-log behavior with
 * tools that don't go through the accessibility gate.
 */
class ToolInterceptorTest {

    private lateinit var interceptor: ToolInterceptor

    @Before
    fun setUp() {
        interceptor = ToolInterceptor()
    }

    @Test
    fun `safe tool is allowed`() {
        val blocked = interceptor.beforeExecute("web_search", mapOf("query" to "k"))
        assertThat(blocked).isNull()
    }

    @Test
    fun `default rate limit blocks the 11th call to a non-messaging tool`() {
        repeat(10) {
            assertThat(interceptor.beforeExecute("web_search", emptyMap())).isNull()
        }
        val blocked = interceptor.beforeExecute("web_search", emptyMap())
        assertThat(blocked).isNotNull()
        assertThat(blocked).contains("Rate limited")
        assertThat(blocked).contains("web_search")
    }

    @Test
    fun `messaging tools have a stricter rate limit of 3 per turn`() {
        repeat(3) {
            assertThat(interceptor.beforeExecute("send_sms", emptyMap())).isNull()
        }
        val blocked = interceptor.beforeExecute("send_sms", emptyMap())
        assertThat(blocked).isNotNull()
        assertThat(blocked).contains("Rate limited")
    }

    @Test
    fun `resetTurnCounters clears per-turn counts`() {
        repeat(3) { interceptor.beforeExecute("send_sms", emptyMap()) }
        assertThat(interceptor.beforeExecute("send_sms", emptyMap())).isNotNull()

        interceptor.resetTurnCounters()
        assertThat(interceptor.beforeExecute("send_sms", emptyMap())).isNull()
    }

    @Test
    fun `getTurnStats reports current per-tool call counts`() {
        interceptor.beforeExecute("web_search", emptyMap())
        interceptor.beforeExecute("web_search", emptyMap())
        interceptor.beforeExecute("memory", mapOf("action" to "save"))
        val stats = interceptor.getTurnStats()
        assertThat(stats["web_search"]).isEqualTo(2)
        assertThat(stats["memory"]).isEqualTo(1)
    }

    @Test
    fun `isDestructive matches the documented destructive set`() {
        assertThat(interceptor.isDestructive("send_sms")).isTrue()
        assertThat(interceptor.isDestructive("send_whatsapp")).isTrue()
        assertThat(interceptor.isDestructive("make_phone_call")).isTrue()
        assertThat(interceptor.isDestructive("control_app_ui")).isTrue()

        assertThat(interceptor.isDestructive("web_search")).isFalse()
        assertThat(interceptor.isDestructive("memory")).isFalse()
    }

    @Test
    fun `isSafe matches the documented safe set`() {
        assertThat(interceptor.isSafe("web_search")).isTrue()
        assertThat(interceptor.isSafe("memory")).isTrue()
        assertThat(interceptor.isSafe("clipboard")).isTrue()

        assertThat(interceptor.isSafe("send_sms")).isFalse()
        assertThat(interceptor.isSafe("control_app_ui")).isFalse()
    }

    @Test
    fun `send_whatsapp without group_name is not blocked by accessibility check`() {
        // Without a group_name key, the conditional accessibility gate must not
        // fire — we should fall through to the rate-limit check (and pass it
        // because the per-turn count starts at 0).
        val blocked = interceptor.beforeExecute(
            "send_whatsapp", mapOf("contact_name" to "Mom", "message" to "hi")
        )
        assertThat(blocked).isNull()
    }

    @Test
    fun `send_whatsapp with group_name is blocked when accessibility unavailable`() {
        val blocked = interceptor.beforeExecute(
            "send_whatsapp", mapOf("group_name" to "Family", "message" to "hi")
        )
        assertThat(blocked).isNotNull()
        assertThat(blocked).contains("accessibility")
    }

    @Test
    fun `audit log records executions and afterExecute summarizes inputs`() {
        interceptor.afterExecute(
            "web_search", mapOf("query" to "kotlin"), "Result: ...", durationMs = 42
        )
        val log = interceptor.auditLog
        assertThat(log).hasSize(1)
        val entry = log.single()
        assertThat(entry.toolName).isEqualTo("web_search")
        assertThat(entry.input).contains("kotlin")
        assertThat(entry.success).isTrue()
        assertThat(entry.durationMs).isEqualTo(42L)
    }

    @Test
    fun `audit log treats Error-prefixed results as failures`() {
        interceptor.afterExecute("memory", mapOf("action" to "save"), "Error: boom", 0)
        assertThat(interceptor.auditLog.single().success).isFalse()
    }

    @Test
    fun `audit log is capped at 100 entries`() {
        repeat(150) { i ->
            interceptor.afterExecute("web_search", mapOf("query" to "q$i"), "ok", 1)
        }
        assertThat(interceptor.auditLog).hasSize(100)
        // Oldest entries should have been dropped — q0 not present, q149 is.
        assertThat(interceptor.auditLog.first().input).doesNotContain("q0,")
        assertThat(interceptor.auditLog.last().input).contains("q149")
    }

    @Test
    fun `getAuditSummary returns a friendly empty message when nothing logged`() {
        assertThat(interceptor.getAuditSummary()).isEqualTo("No tool executions recorded.")
    }

    @Test
    fun `getAuditSummary lists recent entries`() {
        interceptor.afterExecute("web_search", mapOf("query" to "k"), "ok", 5)
        interceptor.afterExecute("memory", mapOf("action" to "recall"), "Error: x", 3)
        val summary = interceptor.getAuditSummary()
        assertThat(summary).contains("web_search")
        assertThat(summary).contains("memory")
        assertThat(summary).contains("OK")
        assertThat(summary).contains("ERR")
    }
}

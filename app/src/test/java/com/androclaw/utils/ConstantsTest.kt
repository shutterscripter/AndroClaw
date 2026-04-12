package com.androclaw.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Sanity tests for the Constants tool registry. These guard against accidental
 * removal/duplication of tool names which would silently break the agent loop.
 */
class ConstantsTest {

    @Test
    fun `ALL_TOOL_NAMES has no duplicates`() {
        val names = Constants.ALL_TOOL_NAMES
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `ALL_TOOL_NAMES contains every documented tool category`() {
        val names = Constants.ALL_TOOL_NAMES
        // Communication
        assertThat(names).containsAtLeast(
            "send_sms", "make_phone_call", "send_whatsapp", "send_email", "get_contacts"
        )
        // Web
        assertThat(names).containsAtLeast("web_search", "web_fetch", "browse_web")
        // Files / memory / notes
        assertThat(names).containsAtLeast("file_manager", "memory", "notes")
        // Apps
        assertThat(names).containsAtLeast("open_app", "list_apps", "control_app_ui")
        // Media / device
        assertThat(names).containsAtLeast(
            "media_control", "take_screenshot", "device_info", "brightness_control"
        )
        // GitHub tool
        assertThat(names).contains("github")
        // Set-of-Mark perception tool
        assertThat(names).contains("screen_observe")
    }

    @Test
    fun `tool name format is snake_case ascii`() {
        val nameRegex = Regex("^[a-z][a-z0-9_]*$")
        for (name in Constants.ALL_TOOL_NAMES) {
            assertThat(name).matches(nameRegex.pattern)
        }
    }

    @Test
    fun `system prompt mentions the github tool action surface`() {
        val prompt = Constants.SYSTEM_PROMPT
        assertThat(prompt).contains("github tool")
        assertThat(prompt).contains("list_prs")
        assertThat(prompt).contains("write_file")
    }

    @Test
    fun `system prompt no longer references the removed ping action`() {
        // ping was removed from GitHubToolHandler — make sure the system prompt
        // doesn't accidentally re-suggest it.
        val prompt = Constants.SYSTEM_PROMPT
        assertThat(prompt).doesNotContain("action 'ping'")
        assertThat(prompt).doesNotContain("action \"ping\"")
    }

    @Test
    fun `default model is a known model`() {
        assertThat(Constants.MODEL_OPTIONS.keys).contains(Constants.DEFAULT_MODEL)
    }

    @Test
    fun `prefs keys are stable`() {
        // These keys are persisted on user devices — renaming them is a breaking
        // change. Lock the values via assertions.
        assertThat(Constants.PREFS_NAME).isEqualTo("androclaw_prefs")
        assertThat(Constants.PREF_API_KEY).isEqualTo("api_key")
        assertThat(Constants.PREF_GITHUB_TOKEN).isEqualTo("github_token")
        assertThat(Constants.PREF_PROVIDER).isEqualTo("provider")
        assertThat(Constants.PREF_ONBOARDING_DONE).isEqualTo("onboarding_done")
    }
}

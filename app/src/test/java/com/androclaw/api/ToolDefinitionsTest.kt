package com.androclaw.api

import com.androclaw.utils.Constants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Verifies the tool schemas exposed to the model match Constants.ALL_TOOL_NAMES
 * and have valid input schemas.
 */
class ToolDefinitionsTest {

    @Test
    fun `getAllTools returns one definition per registered tool name`() {
        val defs = ToolDefinitions.getAllTools()
        val defNames = defs.map { it.name }.toSet()
        assertThat(defNames).containsExactlyElementsIn(Constants.ALL_TOOL_NAMES)
    }

    @Test
    fun `every tool has a non-blank description and a valid input schema`() {
        for (tool in ToolDefinitions.getAllTools()) {
            assertThat(tool.description).isNotEmpty()
            assertThat(tool.inputSchema.type).isEqualTo("object")
            // properties may be empty for tools that take no args, but the map
            // itself must exist.
            assertThat(tool.inputSchema.properties).isNotNull()
        }
    }

    @Test
    fun `enabledTools filter narrows the result set`() {
        val whitelist = setOf("web_search", "memory", "github")
        val defs = ToolDefinitions.getAllTools(enabledTools = whitelist)
        assertThat(defs.map { it.name }).containsExactlyElementsIn(whitelist)
    }

    @Test
    fun `unknown enabled tools yield an empty list`() {
        val defs = ToolDefinitions.getAllTools(enabledTools = setOf("not_a_real_tool"))
        assertThat(defs).isEmpty()
    }

    @Test
    fun `github tool action enum no longer contains ping`() {
        val github = ToolDefinitions.getAllTools().single { it.name == "github" }
        val actionProp = github.inputSchema.properties["action"]
        assertThat(actionProp).isNotNull()
        val enumValues = actionProp!!.enum.orEmpty()
        assertThat(enumValues).isNotEmpty()
        assertThat(enumValues).doesNotContain("ping")
        // Sanity: a few stable actions still present
        assertThat(enumValues).containsAtLeast(
            "list_prs", "view_pr", "list_issues", "list_repos", "read_file", "write_file", "api"
        )
    }

    @Test
    fun `github tool requires action`() {
        val github = ToolDefinitions.getAllTools().single { it.name == "github" }
        assertThat(github.inputSchema.required).contains("action")
    }

    @Test
    fun `github tool exposes organization actions`() {
        val github = ToolDefinitions.getAllTools().single { it.name == "github" }
        val enumValues = github.inputSchema.properties["action"]!!.enum.orEmpty()
        assertThat(enumValues).containsAtLeast(
            "list_orgs", "view_org", "list_org_members", "list_org_teams",
            "list_org_issues", "create_repo"
        )
    }

    @Test
    fun `github tool input schema exposes org-related properties`() {
        val github = ToolDefinitions.getAllTools().single { it.name == "github" }
        val props = github.inputSchema.properties
        assertThat(props.keys).containsAtLeast("org", "name", "description", "private", "auto_init")
        assertThat(props["private"]!!.type).isEqualTo("boolean")
        assertThat(props["auto_init"]!!.type).isEqualTo("boolean")
    }

    @Test
    fun `github tool exposes create_branch and create_pr actions`() {
        val github = ToolDefinitions.getAllTools().single { it.name == "github" }
        val enumValues = github.inputSchema.properties["action"]!!.enum.orEmpty()
        assertThat(enumValues).containsAtLeast("create_branch", "create_pr")
    }

    @Test
    fun `github tool input schema exposes pr-and-branch properties`() {
        val github = ToolDefinitions.getAllTools().single { it.name == "github" }
        val props = github.inputSchema.properties
        assertThat(props.keys).containsAtLeast("head", "base", "from_branch", "draft")
        assertThat(props["draft"]!!.type).isEqualTo("boolean")
    }

    @Test
    fun `screen_observe tool is registered with optional app_package`() {
        val obs = ToolDefinitions.getAllTools().single { it.name == "screen_observe" }
        assertThat(obs.description).contains("Set-of-Mark")
        assertThat(obs.inputSchema.properties.keys).contains("app_package")
        // app_package is optional — observe whatever's on screen by default
        assertThat(obs.inputSchema.required).isEmpty()
    }

    @Test
    fun `control_app_ui description advertises tap_mark tap_at and swipe action types`() {
        val ctl = ToolDefinitions.getAllTools().single { it.name == "control_app_ui" }
        assertThat(ctl.description).contains("tap_mark")
        assertThat(ctl.description).contains("tap_at")
        assertThat(ctl.description).contains("swipe")
        assertThat(ctl.description).contains("screen_observe")
    }
}

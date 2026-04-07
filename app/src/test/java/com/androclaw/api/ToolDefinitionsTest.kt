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
}

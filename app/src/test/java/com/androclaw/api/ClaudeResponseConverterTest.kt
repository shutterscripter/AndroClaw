package com.androclaw.api

import com.androclaw.api.models.ClaudeResponse
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Converter
import retrofit2.Retrofit

class ClaudeResponseConverterTest {

    private val factory = ClaudeResponseConverterFactory()
    // Retrofit is required by the Converter.Factory contract but the converter
    // itself doesn't actually use it.
    private val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").build()
    private val jsonMedia = "application/json".toMediaType()

    @Suppress("UNCHECKED_CAST")
    private fun convert(json: String): ClaudeResponse {
        val converter = factory.responseBodyConverter(
            ClaudeResponse::class.java, emptyArray(), retrofit
        ) as Converter<okhttp3.ResponseBody, ClaudeResponse>
        return converter.convert(json.toResponseBody(jsonMedia))!!
    }

    @Test
    fun `factory returns null for unrelated types`() {
        val converter = factory.responseBodyConverter(
            String::class.java, emptyArray(), retrofit
        )
        assertThat(converter).isNull()
    }

    @Test
    fun `parses a plain text response`() {
        val json = """
            {
              "id": "msg_123",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-6",
              "stop_reason": "end_turn",
              "content": [
                { "type": "text", "text": "Hello world" }
              ]
            }
        """.trimIndent()

        val resp = convert(json)
        assertThat(resp.id).isEqualTo("msg_123")
        assertThat(resp.type).isEqualTo("message")
        assertThat(resp.role).isEqualTo("assistant")
        assertThat(resp.model).isEqualTo("claude-sonnet-4-6")
        assertThat(resp.stopReason).isEqualTo("end_turn")
        assertThat(resp.content).hasSize(1)
        assertThat(resp.content[0].type).isEqualTo("text")
        assertThat(resp.content[0].text).isEqualTo("Hello world")
    }

    @Test
    fun `parses a tool_use response with input map`() {
        val json = """
            {
              "id": "msg_456",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-6",
              "stop_reason": "tool_use",
              "content": [
                { "type": "text", "text": "Searching..." },
                {
                  "type": "tool_use",
                  "id": "toolu_1",
                  "name": "web_search",
                  "input": { "query": "kotlin coroutines", "limit": 5 }
                }
              ]
            }
        """.trimIndent()

        val resp = convert(json)
        assertThat(resp.content).hasSize(2)
        val toolUse = resp.content[1]
        assertThat(toolUse.type).isEqualTo("tool_use")
        assertThat(toolUse.id).isEqualTo("toolu_1")
        assertThat(toolUse.name).isEqualTo("web_search")
        assertThat(toolUse.input).isNotNull()
        assertThat(toolUse.input!!["query"]).isEqualTo("kotlin coroutines")
        assertThat(toolUse.input!!["limit"]).isEqualTo(5)
    }

    @Test
    fun `parses tool_use with nested object input`() {
        val json = """
            {
              "id": "msg_789",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-6",
              "content": [
                {
                  "type": "tool_use",
                  "id": "toolu_2",
                  "name": "github",
                  "input": {
                    "action": "list_prs",
                    "filters": { "state": "open", "limit": 20 },
                    "labels": ["bug", "good first issue"]
                  }
                }
              ]
            }
        """.trimIndent()

        val resp = convert(json)
        val toolUse = resp.content.single()
        @Suppress("UNCHECKED_CAST")
        val filters = toolUse.input!!["filters"] as Map<String, Any>
        assertThat(filters["state"]).isEqualTo("open")
        assertThat(filters["limit"]).isEqualTo(20)
        @Suppress("UNCHECKED_CAST")
        val labels = toolUse.input!!["labels"] as List<Any>
        assertThat(labels).containsExactly("bug", "good first issue").inOrder()
    }

    @Test
    fun `gracefully ignores unknown top-level fields`() {
        val json = """
            {
              "id": "msg_999",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-4-6",
              "content": [{ "type": "text", "text": "ok" }],
              "stop_reason": null,
              "usage": { "input_tokens": 10, "output_tokens": 5 },
              "service_tier": "standard",
              "cache_creation_input_tokens": 0
            }
        """.trimIndent()

        val resp = convert(json)
        assertThat(resp.stopReason).isNull()
        assertThat(resp.content).hasSize(1)
        assertThat(resp.content[0].text).isEqualTo("ok")
    }
}

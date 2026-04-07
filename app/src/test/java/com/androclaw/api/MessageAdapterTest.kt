package com.androclaw.api

import com.androclaw.api.models.Message
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test

class MessageAdapterTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(MessageAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(Message::class.java)

    @Test
    fun `serializes a string-content message`() {
        val msg = Message(role = "user", content = "hello")
        val json = adapter.toJson(msg)
        assertThat(json).isEqualTo("""{"role":"user","content":"hello"}""")
    }

    @Test
    fun `deserializes a string-content message`() {
        val msg = adapter.fromJson("""{"role":"user","content":"hi there"}""")!!
        assertThat(msg.role).isEqualTo("user")
        assertThat(msg.content).isEqualTo("hi there")
    }

    @Test
    fun `serializes list content with map blocks`() {
        val msg = Message(
            role = "assistant",
            content = listOf(
                mapOf("type" to "text", "text" to "thinking…"),
                mapOf("type" to "tool_use", "id" to "t1", "name" to "web_search")
            )
        )
        val json = adapter.toJson(msg)
        assertThat(json).contains("\"role\":\"assistant\"")
        assertThat(json).contains("\"type\":\"text\"")
        assertThat(json).contains("\"type\":\"tool_use\"")
        assertThat(json).contains("\"name\":\"web_search\"")
    }

    @Test
    fun `roundtrips list content`() {
        val original = Message(
            role = "user",
            content = listOf(
                mapOf("type" to "tool_result", "tool_use_id" to "t1", "content" to "ok")
            )
        )
        val json = adapter.toJson(original)
        val parsed = adapter.fromJson(json)!!

        assertThat(parsed.role).isEqualTo("user")
        @Suppress("UNCHECKED_CAST")
        val list = parsed.content as List<Map<String, Any>>
        assertThat(list).hasSize(1)
        assertThat(list[0]["type"]).isEqualTo("tool_result")
        assertThat(list[0]["tool_use_id"]).isEqualTo("t1")
        assertThat(list[0]["content"]).isEqualTo("ok")
    }

    @Test
    fun `unknown fields in nested map blocks are preserved on read`() {
        val json = """{"role":"assistant","content":[{"type":"text","text":"hi","extra":"x"}]}"""
        val parsed = adapter.fromJson(json)!!
        @Suppress("UNCHECKED_CAST")
        val list = parsed.content as List<Map<String, Any>>
        assertThat(list[0]["extra"]).isEqualTo("x")
    }

    // Silences the unused-import warning while making it explicit that the
    // adapter wires through Moshi's generic Map adapter machinery.
    @Suppress("unused")
    private val mapType = Types.newParameterizedType(
        Map::class.java, String::class.java, Any::class.java
    )
}

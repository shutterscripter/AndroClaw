package com.androclaw.api

import com.androclaw.api.models.ClaudeResponse
import com.androclaw.api.models.ContentBlock
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Custom Retrofit converter that manually parses ClaudeResponse from JSON,
 * gracefully ignoring unknown fields the API may return (stop_details,
 * cache_creation_input_tokens, service_tier, etc.)
 */
class ClaudeResponseConverterFactory : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        if (type == ClaudeResponse::class.java) {
            return ClaudeResponseConverter()
        }
        return null
    }

    private class ClaudeResponseConverter : Converter<ResponseBody, ClaudeResponse> {
        override fun convert(value: ResponseBody): ClaudeResponse {
            val json = JSONObject(value.string())

            val contentArray = json.getJSONArray("content")
            val contentBlocks = mutableListOf<ContentBlock>()

            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                val blockType = block.getString("type")

                contentBlocks.add(
                    when (blockType) {
                        "text" -> ContentBlock(
                            type = "text",
                            text = block.optString("text", "")
                        )
                        "tool_use" -> ContentBlock(
                            type = "tool_use",
                            id = block.optString("id"),
                            name = block.optString("name"),
                            input = jsonObjectToMap(block.optJSONObject("input"))
                        )
                        else -> ContentBlock(type = blockType)
                    }
                )
            }

            return ClaudeResponse(
                id = json.optString("id", ""),
                type = json.optString("type", ""),
                role = json.optString("role", ""),
                content = contentBlocks,
                model = json.optString("model", ""),
                stopReason = if (json.has("stop_reason") && !json.isNull("stop_reason")) json.getString("stop_reason") else null
            )
        }

        private fun jsonObjectToMap(json: JSONObject?): Map<String, Any>? {
            if (json == null) return null
            val map = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonValueToKotlin(json.get(key))
            }
            return map
        }

        private fun jsonValueToKotlin(value: Any): Any {
            return when (value) {
                is JSONObject -> jsonObjectToMap(value) ?: emptyMap<String, Any>()
                is JSONArray -> {
                    val list = mutableListOf<Any>()
                    for (i in 0 until value.length()) {
                        list.add(jsonValueToKotlin(value.get(i)))
                    }
                    list
                }
                is Int, is Long, is Double, is Float -> value
                is Boolean -> value
                is String -> value
                JSONObject.NULL -> "null"
                else -> value.toString()
            }
        }
    }
}

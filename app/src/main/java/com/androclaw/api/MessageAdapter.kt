package com.androclaw.api

import com.androclaw.api.models.Message
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * Custom Moshi adapter for Message that handles the polymorphic `content` field.
 * Content can be either a String or a List<Map<String, Any>>.
 */
class MessageAdapter {

    @ToJson
    fun toJson(writer: JsonWriter, message: Message, mapAdapter: JsonAdapter<Map<String, Any>>) {
        writer.beginObject()
        writer.name("role").value(message.role)
        writer.name("content")

        when (val content = message.content) {
            is String -> writer.value(content)
            is List<*> -> {
                writer.beginArray()
                for (item in content) {
                    when (item) {
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            mapAdapter.toJson(writer, item as Map<String, Any>)
                        }
                        else -> writer.value(item?.toString())
                    }
                }
                writer.endArray()
            }
            else -> writer.value(content.toString())
        }

        writer.endObject()
    }

    @FromJson
    fun fromJson(reader: JsonReader, mapAdapter: JsonAdapter<Map<String, Any>>): Message {
        var role = ""
        var content: Any = ""

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "role" -> role = reader.nextString()
                "content" -> {
                    content = when (reader.peek()) {
                        JsonReader.Token.STRING -> reader.nextString()
                        JsonReader.Token.BEGIN_ARRAY -> {
                            val list = mutableListOf<Map<String, Any>>()
                            reader.beginArray()
                            while (reader.hasNext()) {
                                mapAdapter.fromJson(reader)?.let { list.add(it) }
                            }
                            reader.endArray()
                            list
                        }
                        else -> {
                            reader.skipValue()
                            ""
                        }
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return Message(role = role, content = content)
    }
}

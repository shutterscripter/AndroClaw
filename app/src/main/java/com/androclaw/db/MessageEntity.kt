package com.androclaw.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user", "assistant", "tool_status"
    val content: String,
    val toolName: String? = null,
    val toolStatus: String? = null, // "executing", "completed", "error"
    val timestamp: Long = System.currentTimeMillis()
)

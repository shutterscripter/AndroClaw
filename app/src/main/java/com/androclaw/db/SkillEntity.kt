package com.androclaw.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skills",
    indices = [Index(value = ["trigger"], unique = true)]
)
data class SkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val trigger: String,       // e.g. "morning" → user types "/morning"
    val prompt: String,        // The instruction sent to the AI
    val description: String = "",
    val category: String = "general",  // e.g. "routine", "productivity", "social", "utility"
    val isBuiltIn: Boolean = false,    // Bundled skills that ship with the app
    val createdAt: Long = System.currentTimeMillis()
)

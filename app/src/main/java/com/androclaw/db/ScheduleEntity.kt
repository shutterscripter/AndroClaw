package com.androclaw.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val prompt: String,              // The AI instruction to execute
    val type: String,                // "once" or "recurring"
    val intervalMinutes: Int = 0,    // For recurring: interval in minutes
    val scheduledAt: Long = 0,       // For one-shot: epoch millis when to run
    val lastRunAt: Long = 0,         // Last execution time
    val nextRunAt: Long = 0,         // Next scheduled execution time
    val isActive: Boolean = true,    // Paused or active
    val workManagerId: String = "",  // WorkManager unique work name for cancellation
    val createdAt: Long = System.currentTimeMillis()
)

package com.androclaw.tools

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.androclaw.db.ScheduleDao
import com.androclaw.db.ScheduleEntity
import com.androclaw.service.ScheduleWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool handler for scheduled AI automation.
 * Inspired by OpenClaw's cron system — supports one-shot and recurring scheduled tasks
 * that run AI prompts with full tool access in the background.
 */
@Singleton
class ScheduleToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleDao: ScheduleDao
) {

    suspend fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String
            ?: return "Missing action (create, list, delete, pause, resume, info, run_now)"

        return try {
            when (action.lowercase()) {
                "create", "add", "schedule" -> createSchedule(input)
                "list" -> listSchedules()
                "delete", "remove", "cancel" -> deleteSchedule(input)
                "pause" -> pauseSchedule(input)
                "resume" -> resumeSchedule(input)
                "info", "get" -> getSchedule(input)
                "run_now" -> runNow(input)
                else -> "Unknown action: $action. Use: create, list, delete, pause, resume, info, run_now"
            }
        } catch (e: Exception) {
            "Schedule operation failed: ${e.message}"
        }
    }

    private suspend fun createSchedule(input: Map<String, Any>): String {
        val name = input["name"] as? String ?: return "Missing 'name' for the schedule."
        val prompt = input["prompt"] as? String ?: return "Missing 'prompt' — what should the AI do."
        val type = (input["type"] as? String)?.lowercase() ?: "once"

        val workManagerId = "schedule_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()

        return when (type) {
            "once" -> {
                val delayMinutes = (input["delay_minutes"] as? Number)?.toLong()
                    ?: return "Missing 'delay_minutes' — how many minutes from now to run."

                val scheduledAt = now + delayMinutes * 60_000L

                val schedule = ScheduleEntity(
                    name = name,
                    prompt = prompt,
                    type = "once",
                    scheduledAt = scheduledAt,
                    nextRunAt = scheduledAt,
                    workManagerId = workManagerId,
                    isActive = true
                )
                val id = scheduleDao.insert(schedule)

                // Schedule one-time work
                val workData = Data.Builder()
                    .putLong(ScheduleWorker.KEY_SCHEDULE_ID, id)
                    .putString(ScheduleWorker.KEY_PROMPT, prompt)
                    .putString(ScheduleWorker.KEY_SCHEDULE_NAME, name)
                    .build()

                val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setInputData(workData)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(workManagerId, ExistingWorkPolicy.REPLACE, request)

                val runTime = formatTime(scheduledAt)
                "Scheduled \"$name\" to run at $runTime (in ${delayMinutes}m) — ID #$id"
            }

            "recurring" -> {
                val intervalMinutes = (input["interval_minutes"] as? Number)?.toInt()
                    ?: return "Missing 'interval_minutes' for recurring schedule (minimum 15)."

                if (intervalMinutes < 15) {
                    return "Minimum interval is 15 minutes (WorkManager limitation)."
                }

                val nextRunAt = now + intervalMinutes * 60_000L

                val schedule = ScheduleEntity(
                    name = name,
                    prompt = prompt,
                    type = "recurring",
                    intervalMinutes = intervalMinutes,
                    nextRunAt = nextRunAt,
                    workManagerId = workManagerId,
                    isActive = true
                )
                val id = scheduleDao.insert(schedule)

                val workData = Data.Builder()
                    .putLong(ScheduleWorker.KEY_SCHEDULE_ID, id)
                    .putString(ScheduleWorker.KEY_PROMPT, prompt)
                    .putString(ScheduleWorker.KEY_SCHEDULE_NAME, name)
                    .build()

                val request = PeriodicWorkRequestBuilder<ScheduleWorker>(
                    intervalMinutes.toLong(), TimeUnit.MINUTES
                )
                    .setInputData(workData)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(workManagerId, ExistingPeriodicWorkPolicy.REPLACE, request)

                "Scheduled \"$name\" to run every ${intervalMinutes}m — ID #$id"
            }

            else -> "Invalid type: '$type'. Use 'once' or 'recurring'."
        }
    }

    private suspend fun listSchedules(): String {
        val schedules = scheduleDao.getAll()
        if (schedules.isEmpty()) return "No scheduled tasks. Use action 'create' to add one."

        return buildString {
            appendLine("Scheduled tasks (${schedules.size}):")
            for (s in schedules) {
                val status = if (s.isActive) "active" else "paused"
                val typeInfo = when (s.type) {
                    "recurring" -> "every ${s.intervalMinutes}m"
                    "once" -> "at ${formatTime(s.scheduledAt)}"
                    else -> s.type
                }
                val lastRun = if (s.lastRunAt > 0) "last: ${formatTime(s.lastRunAt)}" else "never run"
                appendLine("  #${s.id} \"${s.name}\" [$status] — $typeInfo ($lastRun)")
            }
        }.trimEnd()
    }

    private suspend fun deleteSchedule(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the schedule to delete."

        val schedule = scheduleDao.getById(id) ?: return "Schedule #$id not found."

        // Cancel WorkManager job
        WorkManager.getInstance(context).cancelUniqueWork(schedule.workManagerId)
        scheduleDao.delete(id)
        return "Deleted and cancelled schedule \"${schedule.name}\" (#$id)."
    }

    private suspend fun pauseSchedule(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the schedule to pause."

        val schedule = scheduleDao.getById(id) ?: return "Schedule #$id not found."
        if (!schedule.isActive) return "Schedule \"${schedule.name}\" is already paused."

        // Cancel the WorkManager job
        WorkManager.getInstance(context).cancelUniqueWork(schedule.workManagerId)
        scheduleDao.update(schedule.copy(isActive = false))
        return "Paused schedule \"${schedule.name}\" (#$id)."
    }

    private suspend fun resumeSchedule(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the schedule to resume."

        val schedule = scheduleDao.getById(id) ?: return "Schedule #$id not found."
        if (schedule.isActive) return "Schedule \"${schedule.name}\" is already active."

        // Re-schedule the WorkManager job
        val workData = Data.Builder()
            .putLong(ScheduleWorker.KEY_SCHEDULE_ID, schedule.id)
            .putString(ScheduleWorker.KEY_PROMPT, schedule.prompt)
            .putString(ScheduleWorker.KEY_SCHEDULE_NAME, schedule.name)
            .build()

        if (schedule.type == "recurring" && schedule.intervalMinutes > 0) {
            val request = PeriodicWorkRequestBuilder<ScheduleWorker>(
                schedule.intervalMinutes.toLong(), TimeUnit.MINUTES
            )
                .setInputData(workData)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(schedule.workManagerId, ExistingPeriodicWorkPolicy.REPLACE, request)
        } else {
            val delayMs = maxOf(schedule.scheduledAt - System.currentTimeMillis(), 60_000L)
            val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workData)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(schedule.workManagerId, ExistingWorkPolicy.REPLACE, request)
        }

        scheduleDao.update(schedule.copy(isActive = true))
        return "Resumed schedule \"${schedule.name}\" (#$id)."
    }

    private suspend fun getSchedule(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id'."

        val s = scheduleDao.getById(id) ?: return "Schedule #$id not found."

        return buildString {
            appendLine("Schedule #${s.id}: ${s.name}")
            appendLine("Type: ${s.type}")
            appendLine("Status: ${if (s.isActive) "active" else "paused"}")
            if (s.type == "recurring") appendLine("Interval: ${s.intervalMinutes} minutes")
            if (s.scheduledAt > 0) appendLine("Scheduled at: ${formatTime(s.scheduledAt)}")
            if (s.lastRunAt > 0) appendLine("Last run: ${formatTime(s.lastRunAt)}")
            if (s.nextRunAt > 0) appendLine("Next run: ${formatTime(s.nextRunAt)}")
            appendLine("Prompt: ${s.prompt}")
        }.trimEnd()
    }

    private suspend fun runNow(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the schedule to run immediately."

        val schedule = scheduleDao.getById(id) ?: return "Schedule #$id not found."

        val workData = Data.Builder()
            .putLong(ScheduleWorker.KEY_SCHEDULE_ID, schedule.id)
            .putString(ScheduleWorker.KEY_PROMPT, schedule.prompt)
            .putString(ScheduleWorker.KEY_SCHEDULE_NAME, schedule.name)
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInputData(workData)
            .build()

        WorkManager.getInstance(context)
            .enqueue(request)

        return "Running \"${schedule.name}\" now. Result will appear as a notification."
    }

    private fun formatTime(epochMs: Long): String {
        if (epochMs <= 0) return "N/A"
        return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMs))
    }
}

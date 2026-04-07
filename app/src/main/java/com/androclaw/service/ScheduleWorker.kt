package com.androclaw.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.androclaw.AndroClawApplication
import com.androclaw.R
import com.androclaw.api.SystemPromptManager
import com.androclaw.api.ToolDefinitions
import com.androclaw.api.models.Message
import com.androclaw.api.provider.ProviderRegistry
import com.androclaw.db.ScheduleDao
import com.androclaw.tools.ToolExecutor
import com.androclaw.utils.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import android.content.SharedPreferences
import javax.inject.Named

/**
 * WorkManager worker that executes scheduled AI tasks.
 * Inspired by OpenClaw's cron system — runs an isolated AI turn with tools,
 * then delivers the result as a notification.
 */
@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val providerRegistry: ProviderRegistry,
    private val toolExecutor: ToolExecutor,
    private val systemPromptManager: SystemPromptManager,
    private val scheduleDao: ScheduleDao,
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    @Named("regular") private val prefs: SharedPreferences
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val KEY_PROMPT = "prompt"
        const val KEY_SCHEDULE_NAME = "schedule_name"
        private const val MAX_TOOL_ITERATIONS = 5
    }

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getLong(KEY_SCHEDULE_ID, -1)
        val prompt = inputData.getString(KEY_PROMPT) ?: return Result.failure()
        val scheduleName = inputData.getString(KEY_SCHEDULE_NAME) ?: "Scheduled Task"

        try {
            // Run a mini agent loop with the prompt
            val response = runAgentLoop(prompt)

            // Deliver result as notification
            sendNotification(scheduleName, response)

            // Update schedule record
            if (scheduleId > 0) {
                val schedule = scheduleDao.getById(scheduleId)
                if (schedule != null) {
                    val now = System.currentTimeMillis()
                    val nextRun = if (schedule.type == "recurring" && schedule.intervalMinutes > 0) {
                        now + schedule.intervalMinutes * 60_000L
                    } else 0L

                    scheduleDao.update(schedule.copy(
                        lastRunAt = now,
                        nextRunAt = nextRun,
                        isActive = schedule.type == "recurring" // One-shot becomes inactive
                    ))
                }
            }

            return Result.success()
        } catch (e: Exception) {
            sendNotification("$scheduleName (failed)", "Error: ${e.message}")
            return Result.retry()
        }
    }

    private suspend fun runAgentLoop(userPrompt: String): String {
        val apiKey = getApiKey() ?: return "No API key configured."
        val provider = resolveProvider() ?: return "No LLM provider configured."
        val model = getModel()
        val systemPrompt = systemPromptManager.buildSystemPrompt()
        val tools = ToolDefinitions.getAllTools(getEnabledTools())

        val messages = mutableListOf<Message>()
        messages.add(Message(role = "user", content = userPrompt))

        var iterations = 0
        while (iterations < MAX_TOOL_ITERATIONS) {
            val result = provider.sendMessage(apiKey, model, systemPrompt, messages, tools.ifEmpty { null }, Constants.MAX_TOKENS)

            val response = result.getOrElse { e ->
                return "LLM error: ${e.message}"
            }

            // Add assistant response to history
            val assistantContent = response.content.map { block ->
                when (block.type) {
                    "text" -> mapOf("type" to "text", "text" to (block.text ?: ""))
                    "tool_use" -> {
                        val map = mutableMapOf<String, Any>(
                            "type" to "tool_use",
                            "id" to (block.id ?: ""),
                            "name" to (block.name ?: "")
                        )
                        block.input?.let { map["input"] = it }
                        map
                    }
                    else -> mapOf("type" to block.type)
                }
            }
            messages.add(Message(role = "assistant", content = assistantContent))

            val toolUseBlocks = response.content.filter { it.type == "tool_use" }

            if (toolUseBlocks.isEmpty() || response.stopReason == "end_turn" || response.stopReason == "stop") {
                return response.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text ?: "" }
                    .trim()
                    .ifBlank { "Task completed." }
            }

            // Execute tools
            val toolResults = mutableListOf<Map<String, Any>>()
            for (block in toolUseBlocks) {
                val toolName = block.name ?: continue
                val toolId = block.id ?: continue
                val toolInput = block.input ?: emptyMap()

                val toolResult = try {
                    toolExecutor.execute(toolName, toolInput)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                toolResults.add(mapOf(
                    "type" to "tool_result",
                    "tool_use_id" to toolId,
                    "content" to toolResult
                ))
            }
            messages.add(Message(role = "user", content = toolResults))
            iterations++
        }

        return "Task completed (max iterations reached)."
    }

    private fun sendNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(applicationContext, AndroClawApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun getApiKey(): String? {
        val providerId = prefs.getString(Constants.PREF_PROVIDER, "claude") ?: "claude"
        val perProviderKey = encryptedPrefs.getString("api_key_$providerId", null)
        if (!perProviderKey.isNullOrBlank()) return perProviderKey
        return encryptedPrefs.getString(Constants.PREF_API_KEY, null)
    }

    private fun resolveProvider() = providerRegistry.getProvider(
        prefs.getString(Constants.PREF_PROVIDER, "claude") ?: "claude"
    )

    private fun getModel(): String =
        prefs.getString(Constants.PREF_MODEL, Constants.DEFAULT_MODEL) ?: Constants.DEFAULT_MODEL

    private fun getEnabledTools(): Set<String> =
        prefs.getStringSet(Constants.PREF_ENABLED_TOOLS, Constants.ALL_TOOL_NAMES.toSet())
            ?: Constants.ALL_TOOL_NAMES.toSet()
}

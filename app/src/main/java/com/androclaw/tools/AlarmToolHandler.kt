package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: "set"

        return when (action.lowercase()) {
            "set", "create" -> setAlarm(input)
            "timer" -> setTimer(input)
            "dismiss" -> dismissAlarm()
            "snooze" -> snoozeAlarm()
            "show" -> showAlarms()
            else -> "Unknown alarm action: $action. Use: set, timer, dismiss, snooze, show"
        }
    }

    private fun setAlarm(input: Map<String, Any>): String {
        val hour = (input["hour"] as? Number)?.toInt()
        val minute = (input["minute"] as? Number)?.toInt() ?: 0
        val message = input["message"] as? String ?: input["label"] as? String
        val days = input["days"] as? List<*> // e.g. ["monday", "tuesday"]

        if (hour == null) return "Missing alarm hour (0-23)"

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            days?.let {
                val dayInts = it.mapNotNull { day -> dayNameToInt(day.toString()) }
                if (dayInts.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_DAYS, ArrayList(dayInts))
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val timeStr = "%02d:%02d".format(hour, minute)
            val dayStr = if (days != null) " on ${days.joinToString(", ")}" else ""
            val labelStr = if (message != null) " — \"$message\"" else ""
            "Alarm set for $timeStr$dayStr$labelStr"
        } catch (e: Exception) {
            "Failed to set alarm: ${e.message}"
        }
    }

    private fun setTimer(input: Map<String, Any>): String {
        val seconds = (input["seconds"] as? Number)?.toInt()
        val minutes = (input["minutes"] as? Number)?.toInt()
        val hours = (input["hours"] as? Number)?.toInt()
        val message = input["message"] as? String

        val totalSeconds = (hours ?: 0) * 3600 + (minutes ?: 0) * 60 + (seconds ?: 0)
        if (totalSeconds <= 0) return "Please specify timer duration (hours, minutes, or seconds)"

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val parts = mutableListOf<String>()
            if (hours != null && hours > 0) parts.add("${hours}h")
            if (minutes != null && minutes > 0) parts.add("${minutes}m")
            if (seconds != null && seconds > 0) parts.add("${seconds}s")
            "Timer set for ${parts.joinToString(" ")}${message?.let { " — \"$it\"" } ?: ""}"
        } catch (e: Exception) {
            "Failed to set timer: ${e.message}"
        }
    }

    private fun dismissAlarm(): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Dismissed current alarm"
        } catch (e: Exception) {
            "Failed to dismiss alarm: ${e.message}"
        }
    }

    private fun snoozeAlarm(): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Snoozed alarm"
        } catch (e: Exception) {
            "Failed to snooze alarm: ${e.message}"
        }
    }

    private fun showAlarms(): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened alarm list"
        } catch (e: Exception) {
            "Failed to open alarms: ${e.message}"
        }
    }

    private fun dayNameToInt(day: String): Int? = when (day.lowercase().trim()) {
        "monday", "mon" -> java.util.Calendar.MONDAY
        "tuesday", "tue" -> java.util.Calendar.TUESDAY
        "wednesday", "wed" -> java.util.Calendar.WEDNESDAY
        "thursday", "thu" -> java.util.Calendar.THURSDAY
        "friday", "fri" -> java.util.Calendar.FRIDAY
        "saturday", "sat" -> java.util.Calendar.SATURDAY
        "sunday", "sun" -> java.util.Calendar.SUNDAY
        else -> null
    }
}

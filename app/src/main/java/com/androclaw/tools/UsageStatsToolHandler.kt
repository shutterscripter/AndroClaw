package com.androclaw.tools

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageStatsToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun execute(input: Map<String, Any>): String {
        if (!hasUsageStatsPermission()) {
            // Try to open the settings page
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
            return "Usage access permission not granted. Please enable AndroClaw in Settings > Apps > Special app access > Usage access, then try again."
        }

        val action = input["action"] as? String ?: input["query"] as? String ?: "today"

        return try {
            when (action.lowercase()) {
                "today" -> getScreenTime("today")
                "yesterday" -> getScreenTime("yesterday")
                "week", "this_week" -> getScreenTime("week")
                "app" -> getAppUsage(input)
                "summary" -> getSummary()
                else -> {
                    // Try to interpret as a period
                    if (action.contains("today")) getScreenTime("today")
                    else if (action.contains("yesterday")) getScreenTime("yesterday")
                    else if (action.contains("week")) getScreenTime("week")
                    else getScreenTime("today")
                }
            }
        } catch (e: Exception) {
            "Failed to get usage stats: ${e.message}"
        }
    }

    private fun getScreenTime(period: String): String {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val cal = Calendar.getInstance()
        val endTime = cal.timeInMillis

        when (period) {
            "today" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            "yesterday" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, -1)
                return buildUsageReport(
                    usageStatsManager,
                    cal.timeInMillis,
                    todayStart,
                    "Yesterday"
                )
            }
            "week" -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }
        }

        val startTime = cal.timeInMillis
        val label = when (period) {
            "today" -> "Today"
            "week" -> "Last 7 days"
            else -> period.replaceFirstChar { it.uppercase() }
        }

        return buildUsageReport(usageStatsManager, startTime, endTime, label)
    }

    private fun buildUsageReport(
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long,
        label: String
    ): String {
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        if (stats.isNullOrEmpty()) {
            return "No usage data available for $label. This can happen if:\n" +
                "- The device was off during this period\n" +
                "- Usage access permission was just granted (data starts collecting from now)\n" +
                "- The system hasn't synced usage data yet"
        }

        // Merge stats by package (multiple days get separate entries)
        val merged = mutableMapOf<String, Long>()
        for (stat in stats) {
            if (stat.totalTimeInForeground > 0) {
                merged[stat.packageName] = (merged[stat.packageName] ?: 0) + stat.totalTimeInForeground
            }
        }

        if (merged.isEmpty()) {
            return "No screen time recorded for $label."
        }

        // Sort by usage time descending
        val sorted = merged.entries
            .sortedByDescending { it.value }
            .take(20)

        val totalMs = merged.values.sum()

        val sb = StringBuilder()
        sb.appendLine("Screen time — $label:")
        sb.appendLine("Total: ${formatDuration(totalMs)}")
        sb.appendLine()

        for ((i, entry) in sorted.withIndex()) {
            val appName = getAppName(entry.key)
            val time = formatDuration(entry.value)
            val pct = if (totalMs > 0) (entry.value * 100 / totalMs) else 0
            sb.appendLine("${i + 1}. $appName — $time ($pct%)")
        }

        val remaining = merged.size - sorted.size
        if (remaining > 0) {
            sb.appendLine("... and $remaining more apps")
        }

        return sb.toString().trim()
    }

    private fun getAppUsage(input: Map<String, Any>): String {
        val appName = input["app_name"] as? String ?: input["app"] as? String
            ?: return "Missing 'app_name' — which app to check usage for."

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Search last 7 days
        val cal = Calendar.getInstance()
        val endTime = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val startTime = cal.timeInMillis

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: return "No usage data available."

        // Find matching package
        val appNameLower = appName.lowercase()
        val matching = stats.filter { stat ->
            val name = getAppName(stat.packageName).lowercase()
            val pkg = stat.packageName.lowercase()
            name.contains(appNameLower) || pkg.contains(appNameLower) ||
                appNameLower.split(" ").all { word -> name.contains(word) || pkg.contains(word) }
        }

        if (matching.isEmpty()) {
            return "No usage data found for \"$appName\". The app may not have been used recently, or the name might be different."
        }

        // Group by day
        val dailyUsage = mutableMapOf<String, Long>()
        val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        var totalTime = 0L
        var lastUsed = 0L

        for (stat in matching) {
            totalTime += stat.totalTimeInForeground
            if (stat.lastTimeUsed > lastUsed) lastUsed = stat.lastTimeUsed

            val day = dateFormat.format(Date(stat.lastTimeUsed))
            dailyUsage[day] = (dailyUsage[day] ?: 0) + stat.totalTimeInForeground
        }

        val resolvedName = getAppName(matching.first().packageName)
        val sb = StringBuilder()
        sb.appendLine("Usage for $resolvedName (last 7 days):")
        sb.appendLine("Total: ${formatDuration(totalTime)}")
        if (lastUsed > 0) {
            sb.appendLine("Last used: ${timeAgo(lastUsed)}")
        }
        sb.appendLine()

        for ((day, ms) in dailyUsage.entries.sortedByDescending { it.value }) {
            if (ms > 0) {
                sb.appendLine("  $day: ${formatDuration(ms)}")
            }
        }

        return sb.toString().trim()
    }

    private fun getSummary(): String {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        // Today
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val todayStart = cal.timeInMillis

        val todayStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, todayStart, now)
        val todayTotal = todayStats?.sumOf { it.totalTimeInForeground } ?: 0
        val todayApps = todayStats?.count { it.totalTimeInForeground > 60_000 } ?: 0

        // Yesterday
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStart = cal.timeInMillis
        val yesterdayStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, yesterdayStart, todayStart)
        val yesterdayTotal = yesterdayStats?.sumOf { it.totalTimeInForeground } ?: 0

        // Week
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val weekStart = cal.timeInMillis
        val weekStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, weekStart, now)
        val weekTotal = weekStats?.sumOf { it.totalTimeInForeground } ?: 0
        val weekDailyAvg = weekTotal / 7

        val sb = StringBuilder()
        sb.appendLine("Screen Time Summary:")
        sb.appendLine("  Today: ${formatDuration(todayTotal)} ($todayApps apps)")
        sb.appendLine("  Yesterday: ${formatDuration(yesterdayTotal)}")
        sb.appendLine("  Weekly total: ${formatDuration(weekTotal)}")
        sb.appendLine("  Daily average: ${formatDuration(weekDailyAvg)}")

        // Comparison
        if (yesterdayTotal > 0 && todayTotal > 0) {
            val diff = todayTotal - yesterdayTotal
            val direction = if (diff > 0) "more" else "less"
            sb.appendLine("  vs yesterday: ${formatDuration(kotlin.math.abs(diff))} $direction")
        }

        return sb.toString().trim()
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            // Clean up package name as fallback
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0m"
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ago"
            hours > 0 -> "${hours}h ago"
            minutes > 0 -> "${minutes}m ago"
            else -> "just now"
        }
    }
}

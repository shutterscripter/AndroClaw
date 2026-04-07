package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.androclaw.service.AndroClawAccessibilityService
import com.androclaw.service.NotificationReaderService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: "show"

        return when (action.lowercase()) {
            "show", "open" -> openNotificationPanel()
            "clear", "dismiss_all" -> clearNotifications()
            "settings" -> openNotificationSettings()
            "read", "list" -> readNotifications(input)
            else -> "Unknown action: $action. Use: show, clear, settings, read"
        }
    }

    private fun readNotifications(input: Map<String, Any>): String {
        val service = NotificationReaderService.instance
            ?: return "Notification reader not enabled. Please enable AndroClaw in Settings > Notifications > Notification access."

        val appFilter = input["app"] as? String
        val maxCount = (input["count"] as? Number)?.toInt() ?: 20

        val notifications = service.getActiveNotificationsSummary(maxCount)
        val filtered = if (appFilter != null) {
            notifications.filter {
                it.appName.contains(appFilter, ignoreCase = true) ||
                    it.packageName.contains(appFilter, ignoreCase = true)
            }
        } else notifications

        if (filtered.isEmpty()) {
            return if (appFilter != null) "No notifications from \"$appFilter\"." else "No notifications."
        }

        val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "Notifications (${filtered.size}):\n" + filtered.joinToString("\n") { n ->
            val time = dateFormat.format(Date(n.time))
            val ago = timeAgo(n.time)
            "${n.appName}: ${n.title}${if (n.text.isNotBlank()) " — ${n.text}" else ""} ($time, $ago)"
        }
    }

    private fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ago"
            minutes > 0 -> "${minutes}m ago"
            else -> "just now"
        }
    }

    private fun openNotificationPanel(): String {
        val service = AndroClawAccessibilityService.instance
        return if (service != null) {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "Opened notification panel"
        } else {
            "Accessibility service needed to open notification panel. Enable it in Settings."
        }
    }

    private fun clearNotifications(): String {
        val service = AndroClawAccessibilityService.instance
        return if (service != null) {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            "Dismissed notification shade"
        } else {
            "Accessibility service needed."
        }
    }

    private fun openNotificationSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened notification settings"
        } catch (e: Exception) {
            "Failed to open notification settings: ${e.message}"
        }
    }
}

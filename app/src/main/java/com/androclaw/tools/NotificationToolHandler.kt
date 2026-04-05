package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.androclaw.service.AndroClawAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
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
            else -> "Unknown action: $action. Use: show, clear, settings"
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

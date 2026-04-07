package com.androclaw.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // No-op — we query on demand
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }

    fun getActiveNotificationsSummary(maxCount: Int = 20): List<NotificationInfo> {
        return try {
            val notifications = activeNotifications ?: return emptyList()
            notifications
                .sortedByDescending { it.postTime }
                .take(maxCount)
                .mapNotNull { sbn ->
                    try {
                        val extras = sbn.notification.extras
                        val title = extras.getCharSequence("android.title")?.toString() ?: ""
                        val text = extras.getCharSequence("android.text")?.toString() ?: ""
                        val appName = getAppName(sbn.packageName)

                        if (title.isBlank() && text.isBlank()) null
                        else NotificationInfo(
                            appName = appName,
                            packageName = sbn.packageName,
                            title = title,
                            text = text,
                            time = sbn.postTime
                        )
                    } catch (_: Exception) { null }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    data class NotificationInfo(
        val appName: String,
        val packageName: String,
        val title: String,
        val text: String,
        val time: Long
    )

    companion object {
        @Volatile
        var instance: NotificationReaderService? = null
    }
}

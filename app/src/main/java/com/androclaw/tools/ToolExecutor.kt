package com.androclaw.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsHandler: SmsToolHandler,
    private val browserHandler: BrowserToolHandler,
    private val wifiHandler: WifiToolHandler,
    private val appLaunchHandler: AppLaunchToolHandler,
    private val calendarHandler: CalendarToolHandler,
    private val contactsHandler: ContactsToolHandler,
    private val whatsAppHandler: WhatsAppToolHandler,
    private val reminderHandler: ReminderToolHandler,
    private val phoneCallHandler: PhoneCallToolHandler,
    private val fileHandler: FileToolHandler,
    private val mediaControlHandler: MediaControlToolHandler,
    private val brightnessHandler: BrightnessToolHandler,
    private val clipboardHandler: ClipboardToolHandler,
    private val alarmHandler: AlarmToolHandler,
    private val deviceInfoHandler: DeviceInfoToolHandler,
    private val screenshotHandler: ScreenshotToolHandler,
    private val shareHandler: ShareToolHandler,
    private val notificationHandler: NotificationToolHandler,
    private val emailHandler: EmailToolHandler,
    private val autoScrollHandler: AutoScrollToolHandler,
    private val appCache: AppCacheManager
) {

    suspend fun execute(toolName: String, input: Map<String, Any>): String {
        return try {
            when (toolName) {
                "send_sms" -> smsHandler.execute(input)
                "open_app" -> appLaunchHandler.execute(input)
                "list_apps" -> appLaunchHandler.listApps(input)
                "browse_web" -> browserHandler.execute(input)
                "toggle_setting" -> wifiHandler.execute(input)
                "send_whatsapp" -> whatsAppHandler.execute(input)
                "create_calendar_event" -> calendarHandler.execute(input)
                "set_reminder" -> reminderHandler.execute(input)
                "get_contacts" -> contactsHandler.execute(input)
                "make_phone_call" -> phoneCallHandler.execute(input)
                "file_manager" -> fileHandler.execute(input)
                "media_control" -> mediaControlHandler.execute(input)
                "brightness_control" -> brightnessHandler.execute(input)
                "clipboard" -> clipboardHandler.execute(input)
                "set_alarm" -> alarmHandler.execute(input)
                "device_info" -> deviceInfoHandler.execute(input)
                "take_screenshot" -> screenshotHandler.execute(input)
                "share_content" -> shareHandler.execute(input)
                "notifications" -> notificationHandler.execute(input)
                "send_email" -> emailHandler.execute(input)
                "auto_scroll_feed" -> autoScrollHandler.execute(input)
                "control_app_ui" -> executeAccessibilityAction(input)
                else -> "Unknown tool: $toolName"
            }
        } catch (e: Exception) {
            "Error executing $toolName: ${e.message}"
        }
    }

    private suspend fun executeAccessibilityAction(input: Map<String, Any>): String {
        val service = com.androclaw.service.AndroClawAccessibilityService.instance
            ?: return "Accessibility service is not enabled. Please enable it in Settings > Accessibility > AndroClaw."

        val appPackage = input["app_package"] as? String ?: return "Missing app_package"
        val actions = input["actions"] as? List<*> ?: return "Missing actions list"

        // Launch the app first if specified
        if (appPackage.isNotBlank()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(appPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                kotlinx.coroutines.delay(1500) // Wait for app to load
            }
        }

        val results = mutableListOf<String>()
        for (action in actions) {
            if (action is Map<*, *>) {
                val type = action["type"] as? String ?: continue
                val result = when (type) {
                    "tap" -> {
                        val target = action["target"] as? String ?: "unknown"
                        if (target.startsWith("id:")) {
                            service.tapById(target.removePrefix("id:"))
                        } else {
                            service.tapByText(target.removePrefix("text:"))
                        }
                    }
                    "type" -> {
                        val text = action["text"] as? String ?: ""
                        service.typeText(text)
                    }
                    "scroll" -> {
                        val direction = action["direction"] as? String ?: "down"
                        service.scroll(direction)
                    }
                    "wait" -> {
                        val ms = (action["ms"] as? Number)?.toLong() ?: 1000L
                        kotlinx.coroutines.delay(ms)
                        "Waited ${ms}ms"
                    }
                    "back" -> service.pressBack()
                    "home" -> service.pressHome()
                    else -> "Unknown action type: $type"
                }
                results.add(result)
                // Small delay between actions for stability
                kotlinx.coroutines.delay(300)
            }
        }
        return results.joinToString("\n")
    }
}

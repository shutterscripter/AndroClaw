package com.androclaw.api

import com.androclaw.api.models.InputSchema
import com.androclaw.api.models.PropertySchema
import com.androclaw.api.models.ToolDefinition

object ToolDefinitions {

    fun getAllTools(enabledTools: Set<String>? = null): List<ToolDefinition> {
        val all = listOf(
            sendSms(),
            makePhoneCall(),
            openApp(),
            listApps(),
            browseWeb(),
            toggleSetting(),
            sendWhatsApp(),
            sendEmail(),
            createCalendarEvent(),
            setReminder(),
            setAlarm(),
            getContacts(),
            fileManager(),
            mediaControl(),
            brightnessControl(),
            clipboard(),
            shareContent(),
            takeScreenshot(),
            deviceInfo(),
            notifications(),
            autoScrollFeed(),
            controlAppUi()
        )
        return if (enabledTools != null) {
            all.filter { it.name in enabledTools }
        } else all
    }

    // --- Communication ---

    private fun sendSms() = ToolDefinition(
        name = "send_sms",
        description = "Send an SMS text message. Can use a contact name (will auto-resolve to phone number) or a phone number directly.",
        inputSchema = InputSchema(
            properties = mapOf(
                "phone_number" to PropertySchema(type = "string", description = "Phone number to send to"),
                "message" to PropertySchema(type = "string", description = "The text message content"),
                "contact_name" to PropertySchema(type = "string", description = "Contact name (will look up number automatically)")
            ),
            required = listOf("message")
        )
    )

    private fun makePhoneCall() = ToolDefinition(
        name = "make_phone_call",
        description = "Make a phone call. Provide a contact name (auto-resolves to number) or a phone number. Example: 'call Alex' or 'call +1234567890'.",
        inputSchema = InputSchema(
            properties = mapOf(
                "contact_name" to PropertySchema(type = "string", description = "Name of the contact to call"),
                "phone_number" to PropertySchema(type = "string", description = "Direct phone number to call")
            )
        )
    )

    private fun sendWhatsApp() = ToolDefinition(
        name = "send_whatsapp",
        description = "Send a WhatsApp message to a contact by name.",
        inputSchema = InputSchema(
            properties = mapOf(
                "contact_name" to PropertySchema(type = "string", description = "Name of the contact"),
                "message" to PropertySchema(type = "string", description = "Message to send")
            ),
            required = listOf("contact_name", "message")
        )
    )

    private fun sendEmail() = ToolDefinition(
        name = "send_email",
        description = "Compose and send an email. Opens the default email app with the fields pre-filled.",
        inputSchema = InputSchema(
            properties = mapOf(
                "to" to PropertySchema(type = "string", description = "Recipient email address(es), comma-separated"),
                "subject" to PropertySchema(type = "string", description = "Email subject"),
                "body" to PropertySchema(type = "string", description = "Email body text"),
                "cc" to PropertySchema(type = "string", description = "CC recipients, comma-separated"),
                "bcc" to PropertySchema(type = "string", description = "BCC recipients, comma-separated")
            ),
            required = listOf("to")
        )
    )

    private fun getContacts() = ToolDefinition(
        name = "get_contacts",
        description = "Search the user's contacts by name. Returns matching names and phone numbers.",
        inputSchema = InputSchema(
            properties = mapOf(
                "name_query" to PropertySchema(type = "string", description = "Name or partial name to search for")
            ),
            required = listOf("name_query")
        )
    )

    // --- Apps ---

    private fun openApp() = ToolDefinition(
        name = "open_app",
        description = "Open an installed app by name. Uses smart fuzzy matching — works with common names like 'insta', 'yt', 'chrome'. Can also deep-link into specific sections.",
        inputSchema = InputSchema(
            properties = mapOf(
                "app_name" to PropertySchema(type = "string", description = "Name of the app (e.g. 'Instagram', 'WhatsApp', 'Chrome', 'Settings')"),
                "action" to PropertySchema(type = "string", description = "Optional deep link URL or section name")
            ),
            required = listOf("app_name")
        )
    )

    private fun listApps() = ToolDefinition(
        name = "list_apps",
        description = "List all installed apps on the device, or filter by name. Useful to check what apps are available before trying to open one.",
        inputSchema = InputSchema(
            properties = mapOf(
                "filter" to PropertySchema(type = "string", description = "Optional filter to search app names/packages")
            )
        )
    )

    // --- Web ---

    private fun browseWeb() = ToolDefinition(
        name = "browse_web",
        description = "Open a URL in the browser or perform a Google search.",
        inputSchema = InputSchema(
            properties = mapOf(
                "url" to PropertySchema(type = "string", description = "URL to open"),
                "search_query" to PropertySchema(type = "string", description = "Google search query")
            )
        )
    )

    // --- Device Settings ---

    private fun toggleSetting() = ToolDefinition(
        name = "toggle_setting",
        description = "Toggle a device setting on or off: WiFi, Bluetooth, Do Not Disturb, Flashlight, or Airplane Mode.",
        inputSchema = InputSchema(
            properties = mapOf(
                "setting" to PropertySchema(
                    type = "string",
                    description = "The setting to toggle",
                    enum = listOf("wifi", "bluetooth", "dnd", "flashlight", "airplane_mode")
                ),
                "enable" to PropertySchema(type = "boolean", description = "true to enable, false to disable")
            ),
            required = listOf("setting", "enable")
        )
    )

    private fun brightnessControl() = ToolDefinition(
        name = "brightness_control",
        description = "Control screen brightness. Can set exact percentage, increase/decrease, enable auto-brightness, or set to max/min.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Action to perform",
                    enum = listOf("set", "get", "auto_on", "auto_off", "increase", "decrease", "max", "min")
                ),
                "percentage" to PropertySchema(type = "number", description = "Brightness percentage 0-100 (for 'set' action)")
            ),
            required = listOf("action")
        )
    )

    // --- Calendar & Time ---

    private fun createCalendarEvent() = ToolDefinition(
        name = "create_calendar_event",
        description = "Create a new calendar event with title, date, time, and optional duration.",
        inputSchema = InputSchema(
            properties = mapOf(
                "title" to PropertySchema(type = "string", description = "Event title"),
                "date" to PropertySchema(type = "string", description = "Event date (YYYY-MM-DD)"),
                "time" to PropertySchema(type = "string", description = "Start time (HH:MM)"),
                "duration_minutes" to PropertySchema(type = "number", description = "Duration in minutes (default 60)")
            ),
            required = listOf("title", "date", "time")
        )
    )

    private fun setReminder() = ToolDefinition(
        name = "set_reminder",
        description = "Set a reminder notification that triggers at a specific time.",
        inputSchema = InputSchema(
            properties = mapOf(
                "message" to PropertySchema(type = "string", description = "Reminder message"),
                "trigger_time" to PropertySchema(type = "string", description = "When to trigger (YYYY-MM-DDTHH:MM:SS)")
            ),
            required = listOf("message", "trigger_time")
        )
    )

    private fun setAlarm() = ToolDefinition(
        name = "set_alarm",
        description = "Set an alarm, timer, or manage alarms. Can create one-time or recurring alarms, set countdown timers, dismiss/snooze active alarms.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Action to perform",
                    enum = listOf("set", "timer", "dismiss", "snooze", "show")
                ),
                "hour" to PropertySchema(type = "number", description = "Alarm hour (0-23, for 'set' action)"),
                "minute" to PropertySchema(type = "number", description = "Alarm minute (0-59, for 'set' action)"),
                "message" to PropertySchema(type = "string", description = "Alarm/timer label"),
                "minutes" to PropertySchema(type = "number", description = "Timer duration in minutes (for 'timer' action)"),
                "seconds" to PropertySchema(type = "number", description = "Timer duration in seconds"),
                "hours" to PropertySchema(type = "number", description = "Timer duration in hours"),
                "days" to PropertySchema(type = "array", description = "Recurring days like ['monday','wednesday','friday']", items = PropertySchema(type = "string"))
            ),
            required = listOf("action")
        )
    )

    // --- Files ---

    private fun fileManager() = ToolDefinition(
        name = "file_manager",
        description = "Find, open, share, or browse files on the device. Can search by name and type (image, video, document, pdf, audio), list directory contents, view recent files, get file info, or share/send files to other apps or contacts.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "File action",
                    enum = listOf("find", "open", "share", "list", "info", "recent")
                ),
                "query" to PropertySchema(type = "string", description = "Search query (for 'find' action)"),
                "path" to PropertySchema(type = "string", description = "File path (for 'open', 'share', 'info' actions)"),
                "file_type" to PropertySchema(type = "string", description = "Filter by type: image, video, audio, document, pdf"),
                "directory" to PropertySchema(type = "string", description = "Directory path (for 'list' action)"),
                "target_app" to PropertySchema(type = "string", description = "Target app package name (for 'share' action)"),
                "max_results" to PropertySchema(type = "number", description = "Max results to return")
            ),
            required = listOf("action")
        )
    )

    // --- Media ---

    private fun mediaControl() = ToolDefinition(
        name = "media_control",
        description = "Control media playback and volume. Play/pause/skip music, adjust volume levels for media/ring/alarm streams, mute/unmute.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Media action",
                    enum = listOf("play", "pause", "play_pause", "next", "previous", "stop", "volume_up", "volume_down", "volume_mute", "volume_unmute", "set_volume", "get_volume")
                ),
                "level" to PropertySchema(type = "number", description = "Volume level (for set_volume)"),
                "percentage" to PropertySchema(type = "number", description = "Volume percentage 0-100 (for set_volume)"),
                "steps" to PropertySchema(type = "number", description = "Number of volume steps (for volume_up/down)"),
                "stream" to PropertySchema(type = "string", description = "Audio stream: media, ring, notification, alarm, call")
            ),
            required = listOf("action")
        )
    )

    // --- Clipboard ---

    private fun clipboard() = ToolDefinition(
        name = "clipboard",
        description = "Copy text to clipboard, read current clipboard content, or clear it.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Clipboard action",
                    enum = listOf("copy", "paste", "read", "clear")
                ),
                "text" to PropertySchema(type = "string", description = "Text to copy (for 'copy' action)")
            ),
            required = listOf("action")
        )
    )

    // --- Sharing ---

    private fun shareContent() = ToolDefinition(
        name = "share_content",
        description = "Share text or links to other apps. Can target a specific app (like WhatsApp, Telegram, Twitter) or open the system share dialog.",
        inputSchema = InputSchema(
            properties = mapOf(
                "text" to PropertySchema(type = "string", description = "Text to share"),
                "url" to PropertySchema(type = "string", description = "URL to share"),
                "target_app" to PropertySchema(type = "string", description = "Target app name to share to (optional)")
            )
        )
    )

    // --- Screenshot ---

    private fun takeScreenshot() = ToolDefinition(
        name = "take_screenshot",
        description = "Take a screenshot of the current screen. Requires accessibility service to be enabled.",
        inputSchema = InputSchema(
            properties = mapOf(
                "delay_ms" to PropertySchema(type = "number", description = "Optional delay in milliseconds before taking screenshot")
            )
        )
    )

    // --- Device Info ---

    private fun deviceInfo() = ToolDefinition(
        name = "device_info",
        description = "Get device information: battery level/status, storage space, RAM usage, network/WiFi status, device model, screen info.",
        inputSchema = InputSchema(
            properties = mapOf(
                "query" to PropertySchema(
                    type = "string",
                    description = "What info to get",
                    enum = listOf("battery", "storage", "memory", "network", "device", "screen", "all")
                )
            ),
            required = listOf("query")
        )
    )

    // --- Notifications ---

    private fun notifications() = ToolDefinition(
        name = "notifications",
        description = "Open the notification panel, dismiss notifications, or open notification settings.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Notification action",
                    enum = listOf("show", "clear", "settings")
                )
            ),
            required = listOf("action")
        )
    )

    // --- Auto-scroll / Feed browsing ---

    private fun autoScrollFeed() = ToolDefinition(
        name = "auto_scroll_feed",
        description = "Auto-scroll through short-form video feeds like Instagram Reels, YouTube Shorts, TikTok, Snapchat Spotlight, or any vertically-scrolling feed. Can open the app and start swiping automatically at a set interval, skip to next/previous, like the current item, or stop scrolling.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "What to do",
                    enum = listOf("scroll", "stop", "next", "previous", "like", "open_feed")
                ),
                "app" to PropertySchema(
                    type = "string",
                    description = "App/feed to scroll: tiktok, instagram_reels, youtube_shorts, snapchat, facebook_reels, reddit, twitter, or any app name"
                ),
                "count" to PropertySchema(type = "number", description = "Number of items to scroll through (default 10)"),
                "interval_seconds" to PropertySchema(type = "number", description = "Seconds between each swipe (default 5). Use higher values to watch each reel longer."),
                "direction" to PropertySchema(type = "string", description = "Scroll direction: up (next, default) or down (previous)")
            ),
            required = listOf("action")
        )
    )

    // --- Accessibility UI Control ---

    private fun controlAppUi() = ToolDefinition(
        name = "control_app_ui",
        description = "Control another app's UI via accessibility. Can tap buttons/text, type in fields, scroll, navigate back/home, and wait between actions. The app is launched automatically. Use this for complex multi-step interactions within other apps.",
        inputSchema = InputSchema(
            properties = mapOf(
                "app_package" to PropertySchema(type = "string", description = "Package name of the target app"),
                "actions" to PropertySchema(
                    type = "array",
                    description = "List of UI actions: {type:'tap', target:'text:ButtonLabel'}, {type:'tap', target:'id:com.app:id/button'}, {type:'type', text:'hello'}, {type:'scroll', direction:'down'}, {type:'wait', ms:1000}, {type:'back'}, {type:'home'}",
                    items = PropertySchema(type = "object", description = "Action with 'type' and params")
                )
            ),
            required = listOf("app_package", "actions")
        )
    )
}

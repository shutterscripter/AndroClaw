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
            controlAppUi(),
            webSearch(),
            webFetch(),
            memory(),
            readSms(),
            callLog(),
            getLocation(),
            notes(),
            textToSpeech(),
            skills(),
            screenTime(),
            schedule(),
            github()
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
        description = "Send a WhatsApp message or file to a contact or group by name. Can send text messages, files (PDFs, images, videos, documents), or both (file with caption). For groups, use group_name instead of contact_name.",
        inputSchema = InputSchema(
            properties = mapOf(
                "contact_name" to PropertySchema(type = "string", description = "Name of the contact (for individual chats)"),
                "group_name" to PropertySchema(type = "string", description = "Name of the WhatsApp group (for group chats)"),
                "message" to PropertySchema(type = "string", description = "Text message to send, or caption when sending a file"),
                "file_path" to PropertySchema(type = "string", description = "Full path of the file to send (e.g. /storage/emulated/0/Download/report.pdf)"),
                "file_name" to PropertySchema(type = "string", description = "File name to search for and send (if you don't have the full path)")
            )
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
        description = "Explore, read, and manage files on the device. Actions: find (search by name/type), open, share, list (directory contents), info, recent, read (view file content with line numbers), tree (directory structure), grep (search inside file contents by text/regex), glob (find files by pattern like '*.pdf' or '**/*.kt').",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "File action",
                    enum = listOf("find", "open", "share", "list", "info", "recent", "read", "tree", "grep", "glob")
                ),
                "query" to PropertySchema(type = "string", description = "Search query (for 'find' action)"),
                "path" to PropertySchema(type = "string", description = "File path (for 'open', 'share', 'info', 'read' actions)"),
                "file_type" to PropertySchema(type = "string", description = "Filter by type: image, video, audio, document, pdf"),
                "directory" to PropertySchema(type = "string", description = "Directory path (for 'list', 'tree', 'grep', 'glob' actions)"),
                "target_app" to PropertySchema(type = "string", description = "Target app package name (for 'share' action)"),
                "max_results" to PropertySchema(type = "number", description = "Max results to return"),
                "pattern" to PropertySchema(type = "string", description = "Glob pattern (for 'glob', e.g. '*.pdf') or text/regex (for 'grep')"),
                "offset" to PropertySchema(type = "number", description = "Line offset to start reading from (for 'read', default 0)"),
                "limit" to PropertySchema(type = "number", description = "Number of lines to read (for 'read', default 200)"),
                "max_depth" to PropertySchema(type = "number", description = "Max directory depth (for 'tree', default 3)"),
                "show_hidden" to PropertySchema(type = "boolean", description = "Show hidden files/dirs (for 'tree', default false)"),
                "case_sensitive" to PropertySchema(type = "boolean", description = "Case-sensitive search (for 'grep', default false)"),
                "context_lines" to PropertySchema(type = "number", description = "Lines of context around matches (for 'grep', default 0)")
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
        description = "Take a screenshot of the current screen. With analyze=true, captures the screen and sends it for visual analysis so you can describe what's on screen. Requires accessibility service.",
        inputSchema = InputSchema(
            properties = mapOf(
                "delay_ms" to PropertySchema(type = "number", description = "Optional delay in milliseconds before taking screenshot"),
                "analyze" to PropertySchema(type = "boolean", description = "If true, capture and analyze the screenshot content (Android 11+). Default false.")
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
        description = "Read, open, or manage notifications. Use 'read' to get actual notification content (titles, messages, app names). Use 'show' to open the panel, 'clear' to dismiss.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Notification action",
                    enum = listOf("show", "clear", "settings", "read")
                ),
                "app" to PropertySchema(type = "string", description = "Filter notifications by app name (for 'read')"),
                "count" to PropertySchema(type = "number", description = "Max notifications to return (for 'read', default 20)")
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

    // --- Web Intelligence ---

    private fun webSearch() = ToolDefinition(
        name = "web_search",
        description = "Search the web and return actual text results. Use this to answer factual questions, look up current information, find news, or research topics. Returns titles, snippets, and URLs.",
        inputSchema = InputSchema(
            properties = mapOf(
                "query" to PropertySchema(type = "string", description = "The search query"),
                "max_results" to PropertySchema(type = "number", description = "Max results to return (default 5)")
            ),
            required = listOf("query")
        )
    )

    private fun webFetch() = ToolDefinition(
        name = "web_fetch",
        description = "Fetch and read the content of a webpage URL. Extracts readable text, strips navigation/ads. Use after web_search to read a specific result, or to read any URL the user provides.",
        inputSchema = InputSchema(
            properties = mapOf(
                "url" to PropertySchema(type = "string", description = "The URL to fetch"),
                "extract_mode" to PropertySchema(
                    type = "string",
                    description = "What to extract",
                    enum = listOf("text", "links", "metadata")
                )
            ),
            required = listOf("url")
        )
    )

    // --- Memory ---

    private fun memory() = ToolDefinition(
        name = "memory",
        description = "Save and recall typed information across conversations. Memories persist even after the conversation ends. Use types to categorize: 'user_profile' for user info (name, role), 'preference' for how the user likes things done, 'fact' for general knowledge, 'instruction' for standing orders to always follow, 'reference' for external links/resources.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Memory action",
                    enum = listOf("save", "recall", "list", "delete", "search", "clear_category", "by_type")
                ),
                "key" to PropertySchema(type = "string", description = "Short label for the memory (for save/recall/delete)"),
                "value" to PropertySchema(type = "string", description = "Content to remember (for save)"),
                "query" to PropertySchema(type = "string", description = "Search query (for recall/search)"),
                "category" to PropertySchema(type = "string", description = "Category to organize memories (default: general)"),
                "type" to PropertySchema(
                    type = "string",
                    description = "Memory type — determines how it's used in the system prompt",
                    enum = listOf("user_profile", "preference", "fact", "instruction", "reference")
                )
            ),
            required = listOf("action")
        )
    )

    // --- SMS Reading ---

    private fun readSms() = ToolDefinition(
        name = "read_sms",
        description = "Read SMS message history. Search by contact name, phone number, or message content. Shows received and sent messages with timestamps.",
        inputSchema = InputSchema(
            properties = mapOf(
                "contact_name" to PropertySchema(type = "string", description = "Filter by contact name"),
                "phone_number" to PropertySchema(type = "string", description = "Filter by phone number"),
                "query" to PropertySchema(type = "string", description = "Search message content"),
                "count" to PropertySchema(type = "number", description = "Number of messages to return (default 10)"),
                "folder" to PropertySchema(
                    type = "string",
                    description = "Which folder to read",
                    enum = listOf("all", "inbox", "sent")
                )
            )
        )
    )

    // --- Call Log ---

    private fun callLog() = ToolDefinition(
        name = "call_log",
        description = "Read call history — missed, incoming, outgoing calls. Shows contact name, call type, duration, and time.",
        inputSchema = InputSchema(
            properties = mapOf(
                "contact_name" to PropertySchema(type = "string", description = "Filter by contact name"),
                "type" to PropertySchema(
                    type = "string",
                    description = "Call type filter",
                    enum = listOf("all", "missed", "incoming", "outgoing", "rejected")
                ),
                "count" to PropertySchema(type = "number", description = "Number of entries to return (default 15)"),
                "days" to PropertySchema(type = "number", description = "Only show calls from last N days")
            )
        )
    )

    // --- Location ---

    private fun getLocation() = ToolDefinition(
        name = "get_location",
        description = "Get the device's current GPS location with address. Returns latitude, longitude, accuracy, speed, and reverse-geocoded address.",
        inputSchema = InputSchema(
            properties = mapOf(
                "accuracy" to PropertySchema(
                    type = "string",
                    description = "Location accuracy",
                    enum = listOf("high", "low")
                ),
                "include_address" to PropertySchema(type = "boolean", description = "Include reverse-geocoded address (default true)")
            )
        )
    )

    // --- Notes ---

    private fun notes() = ToolDefinition(
        name = "notes",
        description = "Create, read, update, delete, list, and search personal notes. Notes are stored locally on the device and persist across conversations. Use for saving information, to-do lists, ideas, or any text the user wants to keep.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Notes action",
                    enum = listOf("create", "read", "update", "delete", "list", "search")
                ),
                "title" to PropertySchema(type = "string", description = "Note title (for create/update)"),
                "content" to PropertySchema(type = "string", description = "Note content (for create/update)"),
                "id" to PropertySchema(type = "number", description = "Note ID (for read/update/delete)"),
                "tags" to PropertySchema(type = "string", description = "Comma-separated tags (for create/update)"),
                "query" to PropertySchema(type = "string", description = "Search query (for search)"),
                "tag" to PropertySchema(type = "string", description = "Filter by tag (for list)")
            ),
            required = listOf("action")
        )
    )

    // --- Text-to-Speech ---

    private fun textToSpeech() = ToolDefinition(
        name = "text_to_speech",
        description = "Speak text aloud using text-to-speech. Use this when the user wants to hear something read out, or for hands-free responses. Can control speed, pitch, and language.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "TTS action",
                    enum = listOf("speak", "stop", "status")
                ),
                "text" to PropertySchema(type = "string", description = "Text to speak aloud (for 'speak' action)"),
                "speed" to PropertySchema(type = "number", description = "Speech rate 0.5-2.0 (default 1.0)"),
                "pitch" to PropertySchema(type = "number", description = "Voice pitch 0.5-2.0 (default 1.0)"),
                "language" to PropertySchema(type = "string", description = "Language code (e.g. 'en-US', 'es', 'hi')")
            ),
            required = listOf("action")
        )
    )

    // --- Skills (Custom Commands) ---

    private fun skills() = ToolDefinition(
        name = "skills",
        description = "Manage custom skills (slash commands). Users can create reusable command shortcuts that chain multiple tools. Skills are organized by category and can be exported/imported as JSON for sharing. Built-in skills include /morning, /summary, /note, /see, /findme, /dnd, /shareloc, /health, /news, /reels. Skills are triggered by typing /<trigger> in chat.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Skills action",
                    enum = listOf("create", "list", "run", "delete", "edit", "info", "export", "import", "by_category")
                ),
                "name" to PropertySchema(type = "string", description = "Skill display name (for create/edit)"),
                "trigger" to PropertySchema(type = "string", description = "Slash command trigger without / (for create/run/delete/export)"),
                "prompt" to PropertySchema(type = "string", description = "The instruction to execute when triggered (for create/edit)"),
                "description" to PropertySchema(type = "string", description = "Short description of what the skill does"),
                "category" to PropertySchema(
                    type = "string",
                    description = "Skill category (for create/edit/by_category/export)",
                    enum = listOf("routine", "productivity", "utility", "social", "general")
                ),
                "id" to PropertySchema(type = "number", description = "Skill ID (for edit/delete)"),
                "json" to PropertySchema(type = "string", description = "JSON array of skills to import (for import action)")
            ),
            required = listOf("action")
        )
    )

    // --- Screen Time / Usage Stats ---

    private fun screenTime() = ToolDefinition(
        name = "screen_time",
        description = "Get screen time and app usage statistics. Shows how long each app was used today, yesterday, or this week. Can also check usage for a specific app. Requires usage access permission (will prompt user to enable it).",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "What to check",
                    enum = listOf("today", "yesterday", "week", "app", "summary")
                ),
                "app_name" to PropertySchema(type = "string", description = "App name to check usage for (for 'app' action)")
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

    // --- GitHub ---

    private fun github() = ToolDefinition(
        name = "github",
        description = "GitHub operations via the GitHub REST API: PRs, issues, CI workflow runs, repos, notifications, search, and direct file editing (read/write/delete files in any repo, committing straight to a branch via the Contents API). Use for: checking PR status/CI, creating or commenting on issues, viewing workflow runs, searching repos, browsing repo contents, and editing files in a repo from your phone. Always pass repo as 'owner/repo'. Requires a GitHub Personal Access Token with appropriate scopes (set in Settings → GitHub) — file writes need contents:write / repo scope.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "GitHub action to perform",
                    enum = listOf(
                        "list_prs", "view_pr", "pr_checks", "create_pr_comment", "merge_pr",
                        "list_issues", "view_issue", "create_issue", "comment_issue", "close_issue",
                        "list_runs", "view_run", "rerun",
                        "list_repos", "list_notifications",
                        "search_repos", "search_issues",
                        "get_user",
                        "read_file", "write_file", "delete_file", "list_dir",
                        "api"
                    )
                ),
                "repo" to PropertySchema(type = "string", description = "Repository in owner/repo form (e.g. 'openclaw/openclaw')"),
                "number" to PropertySchema(type = "number", description = "PR or issue number"),
                "state" to PropertySchema(type = "string", description = "Filter state: open, closed, all (for list_prs/list_issues)", enum = listOf("open", "closed", "all")),
                "limit" to PropertySchema(type = "number", description = "Max results (default 10–20 depending on action)"),
                "title" to PropertySchema(type = "string", description = "Title (for create_issue)"),
                "body" to PropertySchema(type = "string", description = "Body text for issue/PR comment, create_issue, or raw API request body"),
                "merge_method" to PropertySchema(type = "string", description = "Merge method (for merge_pr)", enum = listOf("merge", "squash", "rebase")),
                "run_id" to PropertySchema(type = "number", description = "Workflow run ID (for view_run / rerun)"),
                "failed_only" to PropertySchema(type = "boolean", description = "Only re-run failed jobs (for rerun)"),
                "user" to PropertySchema(type = "string", description = "GitHub username (for list_repos)"),
                "username" to PropertySchema(type = "string", description = "GitHub username (for get_user; omit to get the authenticated user)"),
                "query" to PropertySchema(type = "string", description = "Search query (for search_repos / search_issues — uses GitHub search syntax)"),
                "path" to PropertySchema(type = "string", description = "File path inside the repo (for read_file/write_file/delete_file/list_dir) or raw API path starting with / (for action 'api')"),
                "content" to PropertySchema(type = "string", description = "Full new file content as plain text (for write_file). Will be base64-encoded automatically."),
                "branch" to PropertySchema(type = "string", description = "Branch name for read/write/delete/list_dir (defaults to the repo's default branch)"),
                "sha" to PropertySchema(type = "string", description = "File SHA (optional for write_file/delete_file — auto-fetched if omitted; only needed to avoid the auto-probe)"),
                "message" to PropertySchema(type = "string", description = "Commit message (for write_file/delete_file)"),
                "method" to PropertySchema(type = "string", description = "HTTP method for raw API call", enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE"))
            ),
            required = listOf("action")
        )
    )

    // --- Scheduled Automation ---

    private fun schedule() = ToolDefinition(
        name = "schedule",
        description = "Schedule AI tasks to run automatically in the background. Supports one-shot (run once after a delay) and recurring (run every N minutes, minimum 15). Scheduled tasks execute with full tool access and deliver results as notifications. Like a cron job for your AI assistant.",
        inputSchema = InputSchema(
            properties = mapOf(
                "action" to PropertySchema(
                    type = "string",
                    description = "Schedule action",
                    enum = listOf("create", "list", "delete", "pause", "resume", "info", "run_now")
                ),
                "name" to PropertySchema(type = "string", description = "Name for the scheduled task (for create)"),
                "prompt" to PropertySchema(type = "string", description = "The AI instruction to execute on schedule (for create)"),
                "type" to PropertySchema(
                    type = "string",
                    description = "Schedule type",
                    enum = listOf("once", "recurring")
                ),
                "delay_minutes" to PropertySchema(type = "number", description = "Minutes from now to run (for one-shot)"),
                "interval_minutes" to PropertySchema(type = "number", description = "Minutes between runs (for recurring, minimum 15)"),
                "id" to PropertySchema(type = "number", description = "Schedule ID (for delete/pause/resume/info/run_now)")
            ),
            required = listOf("action")
        )
    )
}

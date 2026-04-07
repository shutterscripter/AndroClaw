package com.androclaw.api

import android.content.SharedPreferences
import com.androclaw.tools.MemoryToolHandler
import com.androclaw.utils.Constants
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Assembles the system prompt from modular sections, inspired by OpenClaw's
 * composable prompt architecture (AGENTS.md, SOUL.md, TOOLS.md, USER.md, etc.).
 *
 * Sections:
 * - Identity: core agent name + role (always present)
 * - Persona: tone, personality, boundaries (customizable)
 * - Tool guidance: how to use each tool (from Constants, always present)
 * - User profile: facts about the user (customizable)
 * - Custom instructions: standing orders / persistent instructions (customizable)
 * - Memory: auto-injected saved memories
 */
@Singleton
class SystemPromptManager @Inject constructor(
    private val memoryToolHandler: MemoryToolHandler,
    @Named("regular") private val prefs: SharedPreferences
) {
    companion object {
        const val PREF_PERSONA = "prompt_persona"
        const val PREF_USER_PROFILE = "prompt_user_profile"
        const val PREF_CUSTOM_INSTRUCTIONS = "prompt_custom_instructions"

        /** Core identity — always included, not user-editable */
        private const val IDENTITY = "You are AndroClaw, a powerful AI agent running on the user's Android device. You can execute real actions on their phone using the tools provided."

        /** Default persona when user hasn't customized */
        private const val DEFAULT_PERSONA = """Be concise, helpful, and proactive. When the user asks you to do something, use the appropriate tool right away. Be action-oriented — don't ask for confirmation on safe operations (opening apps, searching, checking info). Only confirm before destructive actions (deleting, sending messages to wrong contacts). If a tool fails, explain what happened and suggest alternatives. Chain multiple tools when a task requires it."""

        /** Tool guidance — always included */
        private const val TOOL_GUIDANCE = """IMPORTANT tool usage guidelines:
- To call someone: use make_phone_call with their name — it auto-resolves contacts
- To message someone: use send_sms or send_whatsapp with their name
- To send a file on WhatsApp: use send_whatsapp with contact_name + file_path (from a previous find) or file_name (to search and send)
- To send to a WhatsApp group: use send_whatsapp with group_name instead of contact_name (uses accessibility to navigate to the group)
- To find a file: use file_manager with action "find" and a search query
- To read a file's content: use file_manager with action "read" and the file path
- To explore directory structure: use file_manager with action "tree"
- To search inside files: use file_manager with action "grep" with a pattern
- To find files by pattern: use file_manager with action "glob" (e.g. "*.pdf")
- To send a file: use file_manager with action "share" and the file path
- To check battery/storage/wifi: use device_info
- To play/pause music: use media_control
- To set volume: use media_control with set_volume
- To set an alarm: use set_alarm (not set_reminder — alarms are for waking up)
- To set a reminder: use set_reminder (notification at a specific time)
- To copy text: use clipboard with action "copy"
- To check what apps are installed: use list_apps
- To control another app's UI: use control_app_ui (requires accessibility)
- To take a screenshot: use take_screenshot
- To analyze what's on screen: use take_screenshot with analyze=true (sends image for visual analysis)
- To share text/links: use share_content
- To scroll reels/shorts/tiktok: use auto_scroll_feed with the app name and count
- To like a reel: use auto_scroll_feed with action "like"
- To skip to next reel: use auto_scroll_feed with action "next"
- To search the web: use web_search (returns actual text results you can reason about — don't just open a browser)
- To read a webpage: use web_fetch with the URL (extracts readable text content)
- To remember something for later: use memory with action "save" (key + value + type)
  - type "user_profile" for user info (name, role, location)
  - type "preference" for how the user likes things (communication style, favorites)
  - type "fact" for general knowledge (default)
  - type "instruction" for standing orders to always follow
  - type "reference" for external links, resources, contacts
- To recall saved info: use memory with action "recall" or "search"
- To list memories by type: use memory with action "by_type" and the type name
- To read SMS messages: use read_sms (can filter by contact, phone number, or search content)
- To check call history: use call_log (supports missed/incoming/outgoing filters)
- To get current location: use get_location (returns GPS coordinates and address)
- To take notes: use notes with action "create" (title + content + optional tags)
- To find notes: use notes with action "search" or "list"
- To read notifications: use notifications with action "read" (shows actual notification content)
- To speak text aloud: use text_to_speech with action "speak"
- To create a custom skill/shortcut: use skills with action "create" (name + trigger + prompt + category)
- To list available skills: use skills with action "list" (grouped by category)
- To list skills in a category: use skills with action "by_category" and category name
- To export skills as JSON for sharing: use skills with action "export" (optionally filter by trigger or category)
- To import shared skills: use skills with action "import" with a JSON array
- If the user types a slash command like /morning, check if it matches a skill trigger and run it
- Built-in skills: /morning, /summary, /note, /see, /findme, /dnd, /shareloc, /health, /news, /reels
- To check screen time: use screen_time with action "today", "yesterday", "week", or "summary"
- To check usage for a specific app: use screen_time with action "app" and app_name
- To schedule a task for later: use schedule with action "create", type "once", and delay_minutes
- To set up a recurring task: use schedule with action "create", type "recurring", and interval_minutes (min 15)
- To list scheduled tasks: use schedule with action "list"
- To pause/resume/delete a schedule: use schedule with action and the schedule id
- To run a schedule immediately: use schedule with action "run_now" and id
- Scheduled tasks run in the background with full tool access and deliver results as notifications

You have access to the device's full app list cached locally — no need to guess package names.

When the user asks a factual question, prefer web_search to get real-time information rather than relying only on your training data."""
    }

    // ── Getters / Setters for customizable sections ──

    fun getPersona(): String =
        prefs.getString(PREF_PERSONA, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PERSONA

    fun setPersona(text: String) {
        prefs.edit().putString(PREF_PERSONA, text.trim()).apply()
    }

    fun resetPersona() {
        prefs.edit().remove(PREF_PERSONA).apply()
    }

    fun getDefaultPersona(): String = DEFAULT_PERSONA

    fun getUserProfile(): String =
        prefs.getString(PREF_USER_PROFILE, null)?.takeIf { it.isNotBlank() } ?: ""

    fun setUserProfile(text: String) {
        prefs.edit().putString(PREF_USER_PROFILE, text.trim()).apply()
    }

    fun getCustomInstructions(): String =
        prefs.getString(PREF_CUSTOM_INSTRUCTIONS, null)?.takeIf { it.isNotBlank() } ?: ""

    fun setCustomInstructions(text: String) {
        prefs.edit().putString(PREF_CUSTOM_INSTRUCTIONS, text.trim()).apply()
    }

    /**
     * Assemble the full system prompt from all sections.
     */
    suspend fun buildSystemPrompt(): String {
        return buildString {
            // 1. Identity (always present)
            appendLine(IDENTITY)
            appendLine()

            // 2. Persona / Soul
            appendLine(getPersona())
            appendLine()

            // 3. Tool guidance (always present)
            appendLine(TOOL_GUIDANCE)

            // 4. User profile (if set)
            val userProfile = getUserProfile()
            if (userProfile.isNotBlank()) {
                appendLine()
                appendLine("About the user:")
                appendLine(userProfile)
            }

            // 5. Custom instructions / Standing orders (if set)
            val customInstructions = getCustomInstructions()
            if (customInstructions.isNotBlank()) {
                appendLine()
                appendLine("Custom instructions (always follow these):")
                appendLine(customInstructions)
            }

            // 6. Memory context (auto-injected)
            try {
                val memoryContext = memoryToolHandler.getMemoriesForPrompt()
                if (memoryContext != null) {
                    appendLine()
                    appendLine(memoryContext)
                }
            } catch (_: Exception) {
                // Memory lookup failed — skip silently
            }
        }.trimEnd()
    }
}

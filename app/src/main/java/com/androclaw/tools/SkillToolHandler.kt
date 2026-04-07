package com.androclaw.tools

import com.androclaw.db.SkillDao
import com.androclaw.db.SkillEntity
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillToolHandler @Inject constructor(
    private val skillDao: SkillDao
) {

    companion object {
        /** Bundled skills that ship with the app, inspired by OpenClaw's skill library */
        val BUNDLED_SKILLS = listOf(
            SkillEntity(
                name = "Morning Briefing",
                trigger = "morning",
                prompt = "Give me a morning briefing: 1) Check the weather for my location using get_location then web_search, 2) Read my unread notifications, 3) List today's calendar events, 4) Check if I have any unread messages. Present everything in a concise summary.",
                description = "Weather, notifications, calendar, and messages at a glance",
                category = "routine",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Daily Summary",
                trigger = "summary",
                prompt = "Give me an end-of-day summary: 1) Check my screen time for today, 2) List any missed calls, 3) Check unread messages, 4) Show today's calendar events. Wrap up with a brief overview.",
                description = "End-of-day recap of calls, messages, screen time",
                category = "routine",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Quick Note",
                trigger = "note",
                prompt = "Take a quick note with the content I provide. Use the notes tool to create it with an auto-generated title based on the content. Tag it as 'quick'.",
                description = "Quickly capture a thought or idea",
                category = "productivity",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Screenshot & Describe",
                trigger = "see",
                prompt = "Take a screenshot with analyze=true and describe in detail what you see on the screen. If there's text, read it. If there's an app open, identify it and describe the current state.",
                description = "Take a screenshot and describe what's on screen",
                category = "utility",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Find My Phone",
                trigger = "findme",
                prompt = "Help the user locate their phone: 1) Set volume to maximum using media_control, 2) Set an alarm for 1 minute from now, 3) Turn on the flashlight. Tell the user what you did.",
                description = "Max volume + alarm + flashlight to find your phone",
                category = "utility",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Do Not Disturb",
                trigger = "dnd",
                prompt = "Enable Do Not Disturb mode and set brightness to minimum. Confirm to the user that DND is on and screen is dimmed.",
                description = "Enable DND and dim the screen",
                category = "utility",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Share Location",
                trigger = "shareloc",
                prompt = "Get my current location with address, then use share_content to share a Google Maps link of my coordinates. Format: https://maps.google.com/?q=LAT,LNG",
                description = "Share your current location via Google Maps link",
                category = "social",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Device Health",
                trigger = "health",
                prompt = "Check device health: 1) Get device_info for battery, storage, and RAM, 2) Check screen time today for a usage summary. Present as a quick health report.",
                description = "Battery, storage, RAM, and usage report",
                category = "utility",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "News Briefing",
                trigger = "news",
                prompt = "Search the web for today's top news headlines. Give me a concise briefing of the top 5 stories with one-line summaries for each.",
                description = "Top 5 news headlines",
                category = "routine",
                isBuiltIn = true
            ),
            SkillEntity(
                name = "Scroll Reels",
                trigger = "reels",
                prompt = "Open Instagram Reels and auto-scroll through 10 reels. Use auto_scroll_feed with app 'instagram_reels', action 'scroll', count 10.",
                description = "Auto-scroll through 10 Instagram Reels",
                category = "social",
                isBuiltIn = true
            )
        )
    }

    /**
     * Seed bundled skills into the database on first run.
     * Only inserts skills that don't already exist (by trigger).
     */
    suspend fun seedBundledSkills() {
        if (skillDao.countBuiltIn() > 0) return // Already seeded

        for (skill in BUNDLED_SKILLS) {
            val existing = skillDao.getByTrigger(skill.trigger)
            if (existing == null) {
                skillDao.insert(skill)
            }
        }
    }

    suspend fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String
            ?: return "Missing action (create, list, run, delete, edit, info, export, import, by_category)"

        return try {
            when (action.lowercase()) {
                "create", "add" -> createSkill(input)
                "list" -> listSkills(input)
                "run", "execute" -> runSkill(input)
                "delete", "remove" -> deleteSkill(input)
                "edit", "update" -> editSkill(input)
                "info", "get" -> getSkill(input)
                "export" -> exportSkills(input)
                "import" -> importSkills(input)
                "by_category" -> listByCategory(input)
                else -> "Unknown skill action: $action. Use: create, list, run, delete, edit, info, export, import, by_category"
            }
        } catch (e: Exception) {
            "Skill operation failed: ${e.message}"
        }
    }

    private suspend fun createSkill(input: Map<String, Any>): String {
        val name = input["name"] as? String
            ?: return "Missing 'name' for the skill."
        val trigger = (input["trigger"] as? String)?.lowercase()?.replace("/", "")?.replace(" ", "_")
            ?: return "Missing 'trigger' (the slash command, e.g. 'morning' for /morning)."
        val prompt = input["prompt"] as? String
            ?: return "Missing 'prompt' — the instruction to execute when this skill is triggered."
        val description = input["description"] as? String ?: ""
        val category = input["category"] as? String ?: "general"

        // Check for duplicate trigger
        val existing = skillDao.getByTrigger(trigger)
        if (existing != null) {
            return "A skill with trigger /$trigger already exists: \"${existing.name}\". Delete it first or use a different trigger."
        }

        val skill = SkillEntity(
            name = name,
            trigger = trigger,
            prompt = prompt,
            description = description,
            category = category,
            isBuiltIn = false
        )
        val id = skillDao.insert(skill)
        return "Skill created: \"$name\" (/$trigger) in category '$category' — ID #$id"
    }

    private suspend fun listSkills(input: Map<String, Any>): String {
        val skills = skillDao.getAll()
        if (skills.isEmpty()) return "No skills created yet. Use action 'create' to make one."

        // Group by category
        val grouped = skills.groupBy { it.category }
        return buildString {
            appendLine("Skills (${skills.size}):")
            for ((category, categorySkills) in grouped) {
                appendLine("\n[$category]")
                for (s in categorySkills) {
                    val builtInTag = if (s.isBuiltIn) " [built-in]" else ""
                    appendLine("  /${s.trigger} → \"${s.name}\"${if (s.description.isNotBlank()) " — ${s.description}" else ""}$builtInTag")
                }
            }
        }.trimEnd()
    }

    private suspend fun listByCategory(input: Map<String, Any>): String {
        val category = input["category"] as? String
            ?: return "Missing 'category'. Available: routine, productivity, utility, social, general"

        val skills = skillDao.getByCategory(category)
        if (skills.isEmpty()) return "No skills in category '$category'."

        return "Skills in '$category' (${skills.size}):\n" + skills.joinToString("\n") { s ->
            val builtInTag = if (s.isBuiltIn) " [built-in]" else ""
            "- /${s.trigger} → \"${s.name}\"${if (s.description.isNotBlank()) " — ${s.description}" else ""}$builtInTag"
        }
    }

    private suspend fun runSkill(input: Map<String, Any>): String {
        val trigger = (input["trigger"] as? String)?.lowercase()?.replace("/", "")
            ?: return "Missing 'trigger' — which skill to run."

        val skill = skillDao.getByTrigger(trigger)
            ?: return "No skill found with trigger /$trigger. Use 'list' to see available skills."

        // Return the prompt so the AI can execute it
        return "[SKILL_EXECUTE] ${skill.prompt}"
    }

    private suspend fun deleteSkill(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
        val trigger = (input["trigger"] as? String)?.lowercase()?.replace("/", "")

        val skill = when {
            id != null -> skillDao.getById(id)
            trigger != null -> skillDao.getByTrigger(trigger)
            else -> return "Provide 'id' or 'trigger' to identify the skill to delete."
        } ?: return "Skill not found."

        skillDao.delete(skill.id)
        return "Deleted skill \"${skill.name}\" (/${skill.trigger})."
    }

    private suspend fun editSkill(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the skill to edit."

        val existing = skillDao.getById(id) ?: return "Skill #$id not found."

        val updated = existing.copy(
            name = input["name"] as? String ?: existing.name,
            prompt = input["prompt"] as? String ?: existing.prompt,
            description = input["description"] as? String ?: existing.description,
            category = input["category"] as? String ?: existing.category,
            trigger = (input["trigger"] as? String)?.lowercase()?.replace("/", "")?.replace(" ", "_")
                ?: existing.trigger
        )
        skillDao.update(updated)
        return "Updated skill #$id: \"${updated.name}\" (/${updated.trigger}) [${updated.category}]"
    }

    private suspend fun getSkill(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
        val trigger = (input["trigger"] as? String)?.lowercase()?.replace("/", "")

        val skill = when {
            id != null -> skillDao.getById(id)
            trigger != null -> skillDao.getByTrigger(trigger)
            else -> return "Provide 'id' or 'trigger'."
        } ?: return "Skill not found."

        return buildString {
            appendLine("Skill #${skill.id}: ${skill.name}")
            appendLine("Trigger: /${skill.trigger}")
            appendLine("Category: ${skill.category}")
            if (skill.isBuiltIn) appendLine("Type: Built-in")
            if (skill.description.isNotBlank()) appendLine("Description: ${skill.description}")
            appendLine("Prompt: ${skill.prompt}")
        }.trimEnd()
    }

    /**
     * Export skills as JSON for sharing.
     */
    private suspend fun exportSkills(input: Map<String, Any>): String {
        val trigger = (input["trigger"] as? String)?.lowercase()?.replace("/", "")
        val category = input["category"] as? String

        val skills = when {
            trigger != null -> {
                val skill = skillDao.getByTrigger(trigger) ?: return "Skill /$trigger not found."
                listOf(skill)
            }
            category != null -> skillDao.getByCategory(category)
            else -> skillDao.getAll()
        }

        if (skills.isEmpty()) return "No skills to export."

        val json = JSONArray()
        for (skill in skills) {
            json.put(JSONObject().apply {
                put("name", skill.name)
                put("trigger", skill.trigger)
                put("prompt", skill.prompt)
                put("description", skill.description)
                put("category", skill.category)
            })
        }
        return "Exported ${skills.size} skill(s):\n```json\n${json.toString(2)}\n```"
    }

    /**
     * Import skills from JSON.
     */
    private suspend fun importSkills(input: Map<String, Any>): String {
        val jsonStr = input["json"] as? String
            ?: return "Missing 'json' — provide a JSON array of skills to import."

        val jsonArray = try {
            JSONArray(jsonStr)
        } catch (e: Exception) {
            return "Invalid JSON: ${e.message}"
        }

        var imported = 0
        var skipped = 0

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val trigger = obj.optString("trigger").lowercase().replace("/", "").replace(" ", "_")
            if (trigger.isBlank()) { skipped++; continue }

            val existing = skillDao.getByTrigger(trigger)
            if (existing != null) { skipped++; continue }

            skillDao.insert(SkillEntity(
                name = obj.optString("name", trigger),
                trigger = trigger,
                prompt = obj.optString("prompt", ""),
                description = obj.optString("description", ""),
                category = obj.optString("category", "general"),
                isBuiltIn = false
            ))
            imported++
        }

        return "Imported $imported skill(s), skipped $skipped (duplicate triggers or invalid)."
    }

    /**
     * Check if user input starts with a slash command and resolve to a skill prompt.
     * Returns the skill's prompt if matched, null otherwise.
     */
    suspend fun resolveSlashCommand(userInput: String): String? {
        if (!userInput.startsWith("/")) return null
        val trigger = userInput.removePrefix("/").split(" ").first().lowercase().trim()
        if (trigger.isBlank()) return null

        val skill = skillDao.getByTrigger(trigger) ?: return null
        // If there are extra args after the command, append them
        val args = userInput.removePrefix("/$trigger").trim()
        return if (args.isNotBlank()) {
            "${skill.prompt}\n\nAdditional context: $args"
        } else {
            skill.prompt
        }
    }
}

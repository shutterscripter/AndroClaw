package com.androclaw.tools

import com.androclaw.db.MemoryDao
import com.androclaw.db.MemoryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryToolHandler @Inject constructor(
    private val memoryDao: MemoryDao
) {

    companion object {
        /** Valid memory types, inspired by OpenClaw's typed memory system */
        val VALID_TYPES = setOf("user_profile", "preference", "fact", "instruction", "reference")
    }

    suspend fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String
            ?: return "Missing action (save, recall, list, delete, clear_category, search, by_type)"

        return try {
            when (action.lowercase()) {
                "save", "remember", "store" -> saveMemory(input)
                "recall", "get", "retrieve" -> recallMemory(input)
                "list" -> listMemories(input)
                "delete", "forget", "remove" -> deleteMemory(input)
                "clear_category" -> clearCategory(input)
                "search" -> searchMemories(input)
                "by_type" -> listByType(input)
                else -> "Unknown memory action: $action. Use: save, recall, list, delete, search, clear_category, by_type"
            }
        } catch (e: Exception) {
            "Memory operation failed: ${e.message}"
        }
    }

    private suspend fun saveMemory(input: Map<String, Any>): String {
        val key = input["key"] as? String
            ?: return "Missing 'key' — a short label for this memory."
        val value = input["value"] as? String
            ?: return "Missing 'value' — the content to remember."
        val category = input["category"] as? String ?: "general"
        val type = (input["type"] as? String)?.lowercase() ?: "fact"

        if (type !in VALID_TYPES) {
            return "Invalid type '$type'. Use: ${VALID_TYPES.joinToString(", ")}"
        }

        // Enforce a reasonable limit
        val count = memoryDao.count()
        if (count >= 100) {
            val existing = memoryDao.getByKey(key)
            if (existing == null) {
                return "Memory is full (100 entries). Delete some memories first, or update an existing one."
            }
        }

        val existing = memoryDao.getByKey(key)
        val entity = MemoryEntity(
            id = existing?.id ?: 0,
            key = key,
            value = value,
            category = category,
            type = type,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        memoryDao.upsert(entity)

        return if (existing != null) {
            "Updated memory \"$key\" = \"$value\" [type: $type, category: $category]"
        } else {
            "Saved memory \"$key\" = \"$value\" [type: $type, category: $category]"
        }
    }

    private suspend fun recallMemory(input: Map<String, Any>): String {
        val key = input["key"] as? String
        val query = input["query"] as? String

        if (key != null) {
            val entity = memoryDao.getByKey(key)
                ?: return "No memory found with key \"$key\"."
            return "Memory \"${entity.key}\": ${entity.value} (category: ${entity.category})"
        }

        if (query != null) {
            val results = memoryDao.search(query)
            return if (results.isEmpty()) {
                "No memories matching \"$query\"."
            } else {
                "Found ${results.size} memory(ies):\n" + results.joinToString("\n") { m ->
                    "- ${m.key}: ${m.value} (${m.category})"
                }
            }
        }

        return "Provide 'key' for exact lookup or 'query' for search."
    }

    private suspend fun listMemories(input: Map<String, Any>): String {
        val category = input["category"] as? String
        val memories = if (category != null) {
            memoryDao.getByCategory(category)
        } else {
            memoryDao.getAll()
        }

        if (memories.isEmpty()) {
            return if (category != null) "No memories in category \"$category\"." else "No memories saved yet."
        }

        val header = if (category != null) {
            "Memories in \"$category\" (${memories.size}):"
        } else {
            "All memories (${memories.size}):"
        }

        return header + "\n" + memories.joinToString("\n") { m ->
            "- [${m.category}] ${m.key}: ${m.value}"
        }
    }

    private suspend fun deleteMemory(input: Map<String, Any>): String {
        val key = input["key"] as? String
            ?: return "Missing 'key' — which memory to delete."

        val existing = memoryDao.getByKey(key)
            ?: return "No memory found with key \"$key\"."

        memoryDao.delete(key)
        return "Deleted memory \"$key\"."
    }

    private suspend fun clearCategory(input: Map<String, Any>): String {
        val category = input["category"] as? String
            ?: return "Missing 'category' — which category to clear."

        val before = memoryDao.getByCategory(category).size
        memoryDao.deleteByCategory(category)
        return "Cleared $before memory(ies) from category \"$category\"."
    }

    private suspend fun searchMemories(input: Map<String, Any>): String {
        val query = input["query"] as? String
            ?: return "Missing 'query' — what to search for in memories."

        val results = memoryDao.search(query)
        return if (results.isEmpty()) {
            "No memories matching \"$query\"."
        } else {
            "Found ${results.size} memory(ies):\n" + results.joinToString("\n") { m ->
                "- [${m.category}] ${m.key}: ${m.value}"
            }
        }
    }

    private suspend fun listByType(input: Map<String, Any>): String {
        val type = (input["type"] as? String)?.lowercase()
            ?: return "Missing 'type'. Use: ${VALID_TYPES.joinToString(", ")}"

        if (type !in VALID_TYPES) {
            return "Invalid type '$type'. Use: ${VALID_TYPES.joinToString(", ")}"
        }

        val memories = memoryDao.getByType(type)
        if (memories.isEmpty()) return "No memories of type '$type'."

        return "Memories of type '$type' (${memories.size}):\n" + memories.joinToString("\n") { m ->
            "- [${m.category}] ${m.key}: ${m.value}"
        }
    }

    /**
     * Called by SystemPromptManager to inject memories into system prompt.
     * Groups memories by type for structured injection, inspired by
     * OpenClaw's typed memory system (user, feedback, project, reference).
     */
    suspend fun getMemoriesForPrompt(): String? {
        val memories = memoryDao.getAll().take(100)
        if (memories.isEmpty()) return null

        val grouped = memories.groupBy { it.type }

        return buildString {
            // User profile facts first — most important for personalization
            grouped["user_profile"]?.let { items ->
                appendLine("About the user (from memory):")
                items.forEach { appendLine("- ${it.key}: ${it.value}") }
                appendLine()
            }

            // Preferences
            grouped["preference"]?.let { items ->
                appendLine("User preferences:")
                items.forEach { appendLine("- ${it.key}: ${it.value}") }
                appendLine()
            }

            // Standing instructions
            grouped["instruction"]?.let { items ->
                appendLine("Standing instructions (always follow):")
                items.forEach { appendLine("- ${it.key}: ${it.value}") }
                appendLine()
            }

            // References
            grouped["reference"]?.let { items ->
                appendLine("Reference info:")
                items.forEach { appendLine("- ${it.key}: ${it.value}") }
                appendLine()
            }

            // General facts
            grouped["fact"]?.let { items ->
                appendLine("Known facts:")
                items.forEach { appendLine("- ${it.key}: ${it.value}") }
            }

            // Any other types (from old data before migration)
            val knownTypes = setOf("user_profile", "preference", "instruction", "reference", "fact")
            val other = grouped.filterKeys { it !in knownTypes }
            for ((type, items) in other) {
                appendLine("Other ($type):")
                items.forEach { appendLine("- ${it.key}: ${it.value}") }
            }
        }.trimEnd()
    }
}

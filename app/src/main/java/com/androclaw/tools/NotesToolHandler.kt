package com.androclaw.tools

import com.androclaw.db.NoteDao
import com.androclaw.db.NoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotesToolHandler @Inject constructor(
    private val noteDao: NoteDao
) {

    suspend fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String
            ?: return "Missing action (create, read, update, delete, list, search)"

        return try {
            when (action.lowercase()) {
                "create", "add", "new" -> createNote(input)
                "read", "get", "view" -> readNote(input)
                "update", "edit" -> updateNote(input)
                "delete", "remove" -> deleteNote(input)
                "list" -> listNotes(input)
                "search", "find" -> searchNotes(input)
                else -> "Unknown notes action: $action. Use: create, read, update, delete, list, search"
            }
        } catch (e: Exception) {
            "Notes operation failed: ${e.message}"
        }
    }

    private suspend fun createNote(input: Map<String, Any>): String {
        val title = input["title"] as? String ?: return "Missing 'title' for the note."
        val content = input["content"] as? String ?: ""
        val tags = input["tags"] as? String ?: ""

        val note = NoteEntity(
            title = title,
            content = content,
            tags = tags
        )
        val id = noteDao.insert(note)
        return "Note created (ID: $id): \"$title\"" + if (tags.isNotBlank()) " [tags: $tags]" else ""
    }

    private suspend fun readNote(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
        val title = input["title"] as? String

        if (id != null) {
            val note = noteDao.getById(id) ?: return "Note #$id not found."
            return formatNote(note)
        }

        if (title != null) {
            val results = noteDao.search(title)
            if (results.isEmpty()) return "No note found matching \"$title\"."
            return formatNote(results.first())
        }

        return "Provide 'id' or 'title' to read a note."
    }

    private suspend fun updateNote(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the note to update."

        val existing = noteDao.getById(id) ?: return "Note #$id not found."

        val updated = existing.copy(
            title = input["title"] as? String ?: existing.title,
            content = input["content"] as? String ?: existing.content,
            tags = input["tags"] as? String ?: existing.tags,
            updatedAt = System.currentTimeMillis()
        )
        noteDao.update(updated)
        return "Note #$id updated: \"${updated.title}\""
    }

    private suspend fun deleteNote(input: Map<String, Any>): String {
        val id = (input["id"] as? Number)?.toLong()
            ?: return "Missing 'id' of the note to delete."

        val existing = noteDao.getById(id) ?: return "Note #$id not found."
        noteDao.delete(id)
        return "Deleted note #$id: \"${existing.title}\""
    }

    private suspend fun listNotes(input: Map<String, Any>): String {
        val tag = input["tag"] as? String
        val limit = (input["limit"] as? Number)?.toInt() ?: 20

        val notes = if (tag != null) {
            noteDao.getByTag(tag)
        } else {
            noteDao.getRecent(limit)
        }

        if (notes.isEmpty()) {
            return if (tag != null) "No notes with tag \"$tag\"." else "No notes yet."
        }

        val header = if (tag != null) "Notes tagged \"$tag\" (${notes.size}):" else "Notes (${notes.size}):"
        return header + "\n" + notes.joinToString("\n") { n ->
            val tags = if (n.tags.isNotBlank()) " [${n.tags}]" else ""
            val preview = n.content.take(60).replace("\n", " ")
            "#${n.id} — ${n.title}$tags: $preview${if (n.content.length > 60) "..." else ""}"
        }
    }

    private suspend fun searchNotes(input: Map<String, Any>): String {
        val query = input["query"] as? String
            ?: return "Missing 'query' to search notes."

        val results = noteDao.search(query)
        if (results.isEmpty()) return "No notes matching \"$query\"."

        return "Found ${results.size} note(s):\n" + results.joinToString("\n") { n ->
            val preview = n.content.take(60).replace("\n", " ")
            "#${n.id} — ${n.title}: $preview${if (n.content.length > 60) "..." else ""}"
        }
    }

    private fun formatNote(note: NoteEntity): String {
        val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("Note #${note.id}: ${note.title}")
        if (note.tags.isNotBlank()) sb.appendLine("Tags: ${note.tags}")
        sb.appendLine("Created: ${dateFormat.format(Date(note.createdAt))}")
        if (note.updatedAt != note.createdAt) {
            sb.appendLine("Updated: ${dateFormat.format(Date(note.updatedAt))}")
        }
        sb.appendLine()
        sb.append(note.content)
        return sb.toString().trim()
    }
}

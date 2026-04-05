package com.androclaw.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: "copy"

        return when (action.lowercase()) {
            "copy" -> copyToClipboard(input)
            "paste", "read", "get" -> readClipboard()
            "clear" -> clearClipboard()
            else -> "Unknown clipboard action: $action. Use: copy, paste/read, clear"
        }
    }

    private fun copyToClipboard(input: Map<String, Any>): String {
        val text = input["text"] as? String ?: return "Missing text to copy"
        val label = input["label"] as? String ?: "AndroClaw"

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        return "Copied to clipboard: \"${text.take(100)}${if (text.length > 100) "..." else ""}\""
    }

    private fun readClipboard(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return "Clipboard is empty"

        val clip = clipboard.primaryClip ?: return "Clipboard is empty"
        if (clip.itemCount == 0) return "Clipboard is empty"

        val text = clip.getItemAt(0).coerceToText(context).toString()
        return if (text.isBlank()) "Clipboard is empty" else "Clipboard content: \"$text\""
    }

    private fun clearClipboard(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        return "Clipboard cleared"
    }
}

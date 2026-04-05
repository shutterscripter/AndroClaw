package com.androclaw.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androclaw.db.MessageEntity

@Composable
fun MessageBubble(message: MessageEntity) {
    when (message.role) {
        "user" -> UserBubble(message.content)
        "assistant" -> AssistantBubble(message.content)
        "tool_status" -> ToolStatusChip(message.content, message.toolName)
    }
}

@Composable
private fun UserBubble(content: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .background(Color(0xFF7C4DFF))
                .padding(12.dp)
        ) {
            Text(
                text = content,
                color = Color.White,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun AssistantBubble(content: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
                .animateContentSize()
        ) {
            // Simple markdown-ish rendering: bold and code
            FormattedText(content)
        }
    }
}

@Composable
private fun FormattedText(text: String) {
    // Simple approach: render as text with monospace for code blocks
    if (text.contains("```")) {
        Column {
            val parts = text.split("```")
            parts.forEachIndexed { index, part ->
                if (index % 2 == 0) {
                    // Regular text
                    if (part.isNotBlank()) {
                        RichText(part.trim())
                    }
                } else {
                    // Code block
                    val code = part.trimStart().let {
                        // Remove language identifier on first line
                        val lines = it.lines()
                        if (lines.size > 1 && lines[0].matches(Regex("^\\w+$"))) {
                            lines.drop(1).joinToString("\n")
                        } else it
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = code.trim(),
                            color = Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    } else {
        RichText(text)
    }
}

@Composable
private fun RichText(text: String) {
    // Handle **bold** markers simply
    Text(
        text = text.replace("**", ""),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 15.sp
    )
}

@Composable
private fun ToolStatusChip(content: String, toolName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color(0xFFFF6D00)
        )
        Text(
            text = content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFFF6D00),
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

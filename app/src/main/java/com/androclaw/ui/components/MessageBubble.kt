package com.androclaw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.androclaw.db.MessageEntity
import com.androclaw.ui.theme.Accent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(message: MessageEntity) {
    when (message.role) {
        "user" -> UserBubble(message)
        "assistant" -> AssistantBubble(message)
        "tool_status" -> ToolStatusChip(message)
    }
}

@Composable
private fun UserBubble(message: MessageEntity) {
    val time = remember(message.timestamp) { formatTime(message.timestamp) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp))
                .background(Accent)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            CopyButton(message.content)
        }
    }
}

@Composable
private fun AssistantBubble(message: MessageEntity) {
    val time = remember(message.timestamp) { formatTime(message.timestamp) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 60.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(size = 32.dp)

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                FormattedText(message.content)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp, start = 44.dp)
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            CopyButton(message.content)
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Icon(
        imageVector = Icons.Outlined.ContentCopy,
        contentDescription = "Copy",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .size(14.dp)
            .clickable {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
    )
}

@Composable
private fun FormattedText(text: String) {
    // Split the message around ``` code fences; even indices are prose, odd
    // are code blocks (with optional leading language tag).
    val segments = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                val code = segment.trimStart().let {
                    val lines = it.lines()
                    if (lines.size > 1 && lines.first().matches(Regex("^\\w+$"))) {
                        lines.drop(1).joinToString("\n")
                    } else it
                }.trimEnd()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = code,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            } else if (segment.isNotBlank()) {
                MarkdownProse(segment)
            }
        }
    }
}

/**
 * Renders a prose segment (no fenced code blocks) as a column of paragraphs,
 * headings, and list items with inline bold/italic/code/link formatting.
 */
@Composable
private fun MarkdownProse(text: String) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    LinkableText(
                        text = block.text,
                        style = style.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MdBlock.Paragraph -> LinkableText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MdBlock.Bullet -> Row {
                    Text(
                        text = "•  ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LinkableText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MdBlock.Numbered -> Row {
                    Text(
                        text = "${block.index}.  ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LinkableText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Renders inline markdown with tappable `[text](url)` links. Each link is
 * registered as a "URL" string annotation on the AnnotatedString; ClickableText
 * looks up the annotation at the tapped offset and opens the URL.
 */
@Composable
private fun LinkableText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val annotated = renderInline(text)
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { ann ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ann.item)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Can't open link", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    )
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val index: Int, val text: String) : MdBlock
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.lines()
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            out.add(MdBlock.Paragraph(paragraph.toString().trim()))
        }
        paragraph.clear()
    }

    val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)")
    val numberRegex = Regex("^\\s*(\\d+)\\.\\s+(.*)")
    val headingRegex = Regex("^(#{1,6})\\s+(.*)")

    for (line in lines) {
        if (line.isBlank()) {
            flushParagraph()
            continue
        }
        val heading = headingRegex.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            continue
        }
        val bullet = bulletRegex.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            out.add(MdBlock.Bullet(bullet.groupValues[1].trim()))
            continue
        }
        val numbered = numberRegex.matchEntire(line)
        if (numbered != null) {
            flushParagraph()
            out.add(
                MdBlock.Numbered(
                    numbered.groupValues[1].toIntOrNull() ?: 1,
                    numbered.groupValues[2].trim()
                )
            )
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(line.trim())
    }
    flushParagraph()
    return out
}

/**
 * Inline markdown: **bold**, *italic*, _italic_, `code`, [text](url).
 * Single-pass scanner — not a full CommonMark parser, but handles what chat
 * models actually produce.
 */
private fun renderInline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        // **bold**
        if (c == '*' && i + 1 < src.length && src[i + 1] == '*') {
            val end = src.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(src.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // *italic*  (single *, but not if next char is space/punct that would
        // make it ambiguous)
        if (c == '*' && i + 1 < src.length && src[i + 1] != ' ' && src[i + 1] != '*') {
            val end = src.indexOf('*', i + 1)
            if (end > 0 && end - i < 80) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // _italic_
        if (c == '_' && i + 1 < src.length && src[i + 1] != ' ' && src[i + 1] != '_') {
            val end = src.indexOf('_', i + 1)
            if (end > 0 && end - i < 80) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // `code`
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > 0) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x22FFFFFF)
                    )
                ) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // [text](url)
        if (c == '[') {
            val closeBracket = src.indexOf(']', i + 1)
            if (closeBracket > 0 && closeBracket + 1 < src.length && src[closeBracket + 1] == '(') {
                val closeParen = src.indexOf(')', closeBracket + 2)
                if (closeParen > 0) {
                    val label = src.substring(i + 1, closeBracket)
                    val url = src.substring(closeBracket + 2, closeParen)
                    val start = length
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF4FC3F7),
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(label)
                    }
                    addStringAnnotation(tag = "URL", annotation = url, start = start, end = length)
                    i = closeParen + 1
                    continue
                }
            }
        }
        append(c)
        i++
    }
}

@Composable
private fun ToolStatusChip(message: MessageEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}

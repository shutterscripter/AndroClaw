package com.androclaw.service

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androclaw.db.MessageEntity
import com.androclaw.ui.components.AppIcon
import com.androclaw.ui.theme.Accent

@Composable
fun OverlayChatUI(
    chatManager: OverlayChatManager,
    visible: Boolean,
    onClose: () -> Unit,
    onResize: ((deltaX: Float, deltaY: Float) -> Unit)? = null,
    onMove: ((deltaX: Float, deltaY: Float) -> Unit)? = null
) {
    val messages by chatManager.messages.collectAsState()
    val isLoading by chatManager.isLoading.collectAsState()
    val toolStatus by chatManager.toolStatus.collectAsState()
    val streamingText by chatManager.streamingText.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll on new messages and streaming text growth
    LaunchedEffect(messages.size, streamingText?.length ?: 0) {
        val hasStreaming = !streamingText.isNullOrEmpty()
        val total = messages.size + (if (hasStreaming) 1 else 0)
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    val transitionState = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { transitionState.targetState = visible }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter = fadeIn(animationSpec = tween(220)) +
                    scaleIn(
                        animationSpec = tween(260),
                        initialScale = 0.85f,
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    ),
            exit = fadeOut(animationSpec = tween(180)) +
                    scaleOut(
                        animationSpec = tween(220),
                        targetScale = 0.85f,
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    )
        ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header — draggable to move the overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onMove != null) {
                            Modifier.pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onMove(dragAmount.x, dragAmount.y)
                                }
                            }
                        } else Modifier
                    )
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(size = 28.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AndroClaw",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isLoading) "Thinking..." else "Online",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLoading) Accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                // New chat
                IconButton(
                    onClick = { chatManager.startNewChat() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "New chat",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                // Close
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                if (messages.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIcon(size = 48.dp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Ask me anything...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    OverlayMessageBubble(msg)
                }

                // Live streaming bubble — token-by-token text
                if (!streamingText.isNullOrEmpty()) {
                    item(key = "streaming-bubble") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 40.dp, top = 3.dp, bottom = 3.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            AppIcon(size = 22.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = streamingText!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                if (isLoading && streamingText.isNullOrEmpty()) {
                    item { OverlayThinkingDots(toolStatus) }
                }
            }

            // Input
            OverlayInputBar(
                isLoading = isLoading,
                onSend = { chatManager.sendMessage(it) }
            )
        }

        // Resize handle at bottom-right corner
        if (onResize != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onResize(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Three diagonal lines as resize grip
                Text(
                    text = "⋮⋮",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.rotate(315f)
                )
            }
        }
    }
        }
    }
}

@Composable
private fun OverlayMessageBubble(message: MessageEntity) {
    when (message.role) {
        "user" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                        .background(Accent)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        "assistant" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 40.dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                AppIcon(size = 22.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = message.content.replace("**", ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        "tool_status" -> {
            Text(
                text = message.content,
                style = MaterialTheme.typography.labelSmall,
                color = Accent.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OverlayThinkingDots(toolStatus: String?) {
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(size = 22.dp)
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val transition = rememberInfiniteTransition(label = "dots")
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.2f, targetValue = 0.8f,
                    animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 180), RepeatMode.Reverse),
                    label = "d$i"
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = alpha))
                )
                if (i < 2) Spacer(modifier = Modifier.width(3.dp))
            }
            if (toolStatus != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = toolStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OverlayInputBar(isLoading: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank() && !isLoading

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp)),
            placeholder = {
                Text(
                    if (isLoading) "Thinking..." else "Message...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) { onSend(text); text = "" }
                }
            ),
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(
            onClick = { if (canSend) { onSend(text); text = "" } },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (canSend) Accent else MaterialTheme.colorScheme.outline)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

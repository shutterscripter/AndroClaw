package com.androclaw.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androclaw.ui.theme.Accent
import com.androclaw.utils.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onRequestScreenCapture: () -> Unit = {},
    onStopScreenCapture: () -> Unit = {}
) {
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ── LLM Provider ──
            SettingsSection(title = "LLM Provider", icon = Icons.Outlined.Cloud) {
                var providerExpanded by remember { mutableStateOf(false) }
                var currentProvider by remember { mutableStateOf(viewModel.getProvider()) }
                val allProviders = viewModel.providerRegistry.getAllProviders()
                val providerObj = viewModel.providerRegistry.getProvider(currentProvider)

                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = providerObj?.displayName ?: currentProvider,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent)
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        allProviders.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(provider.displayName, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${provider.supportedModels.size} models" +
                                                if (provider.supportsStreaming) " | Streaming" else "" +
                                                if (provider.supportsTools) " | Tools" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    currentProvider = provider.id
                                    viewModel.setProvider(provider.id)
                                    providerExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── API Key (per provider) ──
            SettingsSection(title = "API Key", icon = Icons.Outlined.Key) {
                var currentProvider by remember { mutableStateOf(viewModel.getProvider()) }
                // Re-read when provider changes
                currentProvider = viewModel.getProvider()
                val providerObj = viewModel.providerRegistry.getProvider(currentProvider)
                var apiKey by remember(currentProvider) { mutableStateOf(viewModel.getApiKeyForProvider(currentProvider)) }
                var showKey by remember { mutableStateOf(false) }

                val placeholder = when (currentProvider) {
                    "claude" -> "sk-ant-api03-..."
                    "openai" -> "sk-..."
                    "gemini" -> "AIza..."
                    "groq" -> "gsk_..."
                    "openrouter" -> "sk-or-v1-..."
                    else -> "Enter API key..."
                }

                Text(
                    text = "API key for ${providerObj?.displayName ?: currentProvider}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        viewModel.setApiKeyForProvider(currentProvider, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle visibility",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )
            }

            // ── Exa Web Search ──
            SettingsSection(title = "Exa Web Search", icon = Icons.Outlined.Key) {
                var exaKey by remember { mutableStateOf(viewModel.getExaApiKey()) }
                var showExaKey by remember { mutableStateOf(false) }

                Text(
                    text = "API key for Exa neural web search (https://exa.ai). When set, web_search uses Exa instead of DuckDuckGo/Google scraping for higher-quality results. Auto-saved as you type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = exaKey,
                    onValueChange = {
                        exaKey = it
                        viewModel.setExaApiKey(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("exa_...") },
                    visualTransformation = if (showExaKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showExaKey = !showExaKey }) {
                            Icon(
                                imageVector = if (showExaKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle visibility",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (exaKey.isNotBlank()) "✓ Active — ${exaKey.length} chars saved. web_search will use Exa." else "Not set — web_search falls back to DuckDuckGo/Google.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (exaKey.isNotBlank()) Accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── GitHub Token ──
            SettingsSection(title = "GitHub", icon = Icons.Outlined.Key) {
                // Don't mask by default — the field is already only visible inside Settings,
                // and masking has been hiding the fact that paste isn't being captured.
                var ghToken by remember { mutableStateOf(viewModel.getGitHubToken()) }
                var savedLen by remember { mutableStateOf(viewModel.getGitHubToken().length) }
                var showGhToken by remember { mutableStateOf(true) }

                Text(
                    text = "Personal Access Token (classic or fine-grained). Used by the github tool for PRs, issues, CI, file edits, and more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ghp_... or github_pat_...") },
                    visualTransformation = if (showGhToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showGhToken = !showGhToken }) {
                            Icon(
                                imageVector = if (showGhToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle visibility",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            viewModel.setGitHubToken(ghToken)
                            savedLen = viewModel.getGitHubToken().length
                        }
                    ) { Text("Save") }
                    TextButton(
                        onClick = {
                            ghToken = ""
                            viewModel.setGitHubToken("")
                            savedLen = 0
                        }
                    ) { Text("Clear") }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (savedLen > 0) "Saved ($savedLen chars)" else "Not saved",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (savedLen > 0) Accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Model Selection (dynamic per provider) ──
            SettingsSection(title = "Model", icon = Icons.Outlined.SmartToy) {
                var expanded by remember { mutableStateOf(false) }
                val models = viewModel.getModelsForCurrentProvider()
                val currentModel = viewModel.getModel()
                val currentModelInfo = models.find { it.id == currentModel }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = currentModelInfo?.displayName ?: currentModel,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            buildString {
                                                append(model.id)
                                                if (model.contextWindow > 0) {
                                                    append(" | ${model.contextWindow / 1000}k ctx")
                                                }
                                                if (model.supportsVision) append(" | Vision")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setModel(model.id)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── Screen capture (MediaProjection) ──
            SettingsSection(title = "Screen capture", icon = Icons.Outlined.SmartToy) {
                val capturing = viewModel.isScreenCaptureRunning()
                Text(
                    text = if (capturing) {
                        "Screen capture is ON. AndroClaw can read the screen instantly via MediaProjection — faster and more reliable than the accessibility-based fallback. A persistent notification stays in your status bar while it's running."
                    } else {
                        "Off. Turn this on to let AndroClaw read the screen via MediaProjection (preferred — faster, no rate limit, works on Android 10). The system will ask permission once. A persistent notification will stay in your status bar while it's running."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (capturing) {
                    TextButton(
                        onClick = onStopScreenCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Stop screen capture") }
                } else {
                    TextButton(
                        onClick = onRequestScreenCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enable screen capture") }
                }
            }

            // ── Persona ──
            SettingsSection(title = "Persona", icon = Icons.Outlined.SmartToy) {
                Text(
                    text = "Customize how AndroClaw behaves and communicates. Leave blank for default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                var persona by remember { mutableStateOf(viewModel.getPersona()) }
                val isDefault = persona == viewModel.getDefaultPersona()

                OutlinedTextField(
                    value = persona,
                    onValueChange = {
                        persona = it
                        viewModel.setPersona(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Be brief and casual. Use humor.") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )

                if (!isDefault) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        viewModel.resetPersona()
                        persona = viewModel.getPersona()
                    }) {
                        Text("Reset to default", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ── User Profile ──
            SettingsSection(title = "About You", icon = Icons.Outlined.Person) {
                Text(
                    text = "Tell AndroClaw about yourself so it can personalize responses. This is injected into every conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                var userProfile by remember { mutableStateOf(viewModel.getUserProfile()) }

                OutlinedTextField(
                    value = userProfile,
                    onValueChange = {
                        userProfile = it
                        viewModel.setUserProfile(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. My name is Alex. I work as a designer. I live in NYC. My partner's name is Sam.") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )
            }

            // ── Custom Instructions ──
            SettingsSection(title = "Custom Instructions", icon = Icons.Outlined.Edit) {
                Text(
                    text = "Standing orders that AndroClaw always follows. Like OpenClaw's AGENTS.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                var instructions by remember { mutableStateOf(viewModel.getCustomInstructions()) }

                OutlinedTextField(
                    value = instructions,
                    onValueChange = {
                        instructions = it
                        viewModel.setCustomInstructions(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Always check my calendar before suggesting meeting times. Respond in Spanish when I write in Spanish.") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )
            }

            // ── Floating Button ──
            SettingsSection(title = "Floating Assistant", icon = Icons.Outlined.ChatBubbleOutline) {
                var floatingEnabled by remember { mutableStateOf(viewModel.isFloatingButtonEnabled()) }
                SettingsToggleRow(
                    title = "Floating button overlay",
                    subtitle = "Quick access from any app",
                    checked = floatingEnabled,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            if (!android.provider.Settings.canDrawOverlays(context)) {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                return@SettingsToggleRow
                            }
                            floatingEnabled = true
                            viewModel.setFloatingButtonEnabled(true)
                            com.androclaw.service.FloatingButtonService.start(context)
                        } else {
                            floatingEnabled = false
                            viewModel.setFloatingButtonEnabled(false)
                            com.androclaw.service.FloatingButtonService.stop(context)
                        }
                    }
                )
            }

            // ── Accessibility ──
            SettingsSection(title = "Accessibility", icon = Icons.Outlined.Accessibility) {
                Text(
                    text = "Required for controlling other apps, taking screenshots, and auto-scrolling feeds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                        .clickable {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Accessibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Accessibility Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Tools ──
            SettingsSection(title = "Enabled Tools") {
                val enabledTools = viewModel.getEnabledTools()
                val toolGroups = mapOf(
                    "Communication" to listOf("send_sms", "read_sms", "make_phone_call", "call_log", "send_whatsapp", "send_email", "get_contacts"),
                    "Apps & Web" to listOf("open_app", "list_apps", "browse_web", "web_search", "web_fetch"),
                    "Device" to listOf("toggle_setting", "brightness_control", "media_control", "device_info", "get_location"),
                    "Productivity" to listOf("create_calendar_event", "set_reminder", "set_alarm", "clipboard", "file_manager", "notes", "memory"),
                    "Advanced" to listOf("take_screenshot", "share_content", "notifications", "auto_scroll_feed", "control_app_ui", "text_to_speech", "skills", "screen_time", "schedule")
                )

                toolGroups.forEach { (groupName, tools) ->
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    tools.forEach { tool ->
                        if (tool in Constants.ALL_TOOL_NAMES) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.toggleTool(tool) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = tool in enabledTools,
                                    onCheckedChange = { viewModel.toggleTool(tool) },
                                    colors = CheckboxDefaults.colors(checkedColor = Accent),
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = formatToolName(tool),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // ── Danger Zone ──
            SettingsSection(title = "Data") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .clickable { showClearDialog = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear Conversation History",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text("Clear History", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                Text(
                    "This will permanently delete all conversation history.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearDialog = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent
            )
        )
    }
}

private fun formatToolName(toolName: String): String {
    return toolName.replace("_", " ").split(" ").joinToString(" ") {
        it.replaceFirstChar { c -> c.uppercase() }
    }
}

package com.androclaw.ui

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androclaw.api.AgentEvent
import com.androclaw.api.BootstrapManager
import com.androclaw.api.ClaudeRepository
import com.androclaw.api.ContextUsageInfo
import com.androclaw.api.models.Message
import com.androclaw.tools.SkillToolHandler
import com.androclaw.db.ConversationDao
import com.androclaw.db.ConversationEntity
import com.androclaw.db.MessageDao
import com.androclaw.db.MessageEntity
import com.androclaw.utils.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class ChatUiState(
    val isLoading: Boolean = false,
    val currentToolStatus: String? = null,
    val error: String? = null,
    val activeConversationId: Long? = null,
    val isDrawerOpen: Boolean = false,
    val streamingText: String? = null,
    val contextUsage: ContextUsageInfo? = null,
    val isCompacting: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ClaudeRepository,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val skillToolHandler: SkillToolHandler,
    private val bootstrapManager: BootstrapManager,
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    @Named("regular") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    // Active conversation ID as a flow for reactive message loading
    private val _activeConversationId = MutableStateFlow<Long?>(null)

    // Messages for the active conversation
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = _activeConversationId
        .flatMapLatest { id ->
            if (id != null) messageDao.getMessagesForConversation(id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // All conversations for the drawer
    val conversations = conversationDao.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // In-memory conversation history for Claude API (resets on conversation switch)
    private val conversationHistory = mutableListOf<Message>()

    init {
        // Restore the shared active conversation id (synced with the floating overlay).
        // Setting the StateFlow synchronously here means ChatScreen sees the right id
        // on first composition and won't auto-create a duplicate "new chat".
        val savedId = prefs.getLong(Constants.PREF_ACTIVE_CONVERSATION_ID, -1L)
        if (savedId > 0L) {
            _activeConversationId.value = savedId
            _uiState.value = _uiState.value.copy(activeConversationId = savedId)
        }

        // Validate the restored id (or pick the most recent / create a new one).
        viewModelScope.launch {
            val current = _activeConversationId.value
            if (current != null) {
                val conv = conversationDao.getConversation(current)
                if (conv == null) {
                    // Stale pref — fall back.
                    selectMostRecentOrCreate()
                }
            } else {
                selectMostRecentOrCreate()
            }
        }

        // Seed bundled skills on first run
        viewModelScope.launch {
            try { skillToolHandler.seedBundledSkills() } catch (_: Exception) {}
        }

        // Observe agent events
        viewModelScope.launch {
            repository.agentEvents.collect { event ->
                when (event) {
                    is AgentEvent.ToolExecuting -> {
                        _uiState.value = _uiState.value.copy(
                            currentToolStatus = event.status.description
                        )
                        val convId = _activeConversationId.value ?: return@collect
                        messageDao.insertMessage(
                            MessageEntity(
                                conversationId = convId,
                                role = "tool_status",
                                content = event.status.description,
                                toolName = event.status.toolName,
                                toolStatus = "executing"
                            )
                        )
                    }
                    is AgentEvent.ToolCompleted -> {
                        _uiState.value = _uiState.value.copy(currentToolStatus = null)
                    }
                    is AgentEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = event.message,
                            currentToolStatus = null,
                            streamingText = null
                        )
                    }
                    is AgentEvent.StreamingText -> {
                        // Streaming text delta — update the current assistant message in-place
                        val convId = _activeConversationId.value ?: return@collect
                        _uiState.value = _uiState.value.copy(
                            currentToolStatus = null,
                            streamingText = (_uiState.value.streamingText ?: "") + event.delta
                        )
                    }
                    is AgentEvent.FinalResponse -> {
                        // Clear streaming buffer
                        _uiState.value = _uiState.value.copy(streamingText = null, isCompacting = false)
                    }
                    is AgentEvent.ContextUpdate -> {
                        _uiState.value = _uiState.value.copy(contextUsage = event.info)
                    }
                    is AgentEvent.Compacting -> {
                        _uiState.value = _uiState.value.copy(
                            isCompacting = true,
                            currentToolStatus = event.message
                        )
                    }
                }
            }
        }
    }

    fun hasApiKey(): Boolean =
        !encryptedPrefs.getString(Constants.PREF_API_KEY, null).isNullOrBlank()

    // ── Conversation Management ──

    fun startNewConversation() {
        viewModelScope.launch {
            val conversation = ConversationEntity()
            val id = conversationDao.insert(conversation)
            switchToConversation(id)

            // First-run bootstrap intentionally disabled — start with an empty chat.
            if (bootstrapManager.shouldRunBootstrap()) {
                bootstrapManager.markBootstrapDone()
            }
        }
    }

    fun switchToConversation(conversationId: Long) {
        _activeConversationId.value = conversationId
        _uiState.value = _uiState.value.copy(
            activeConversationId = conversationId,
            isDrawerOpen = false,
            isLoading = false,
            currentToolStatus = null,
            error = null
        )
        // Persist so the overlay (and next launch) lands on the same chat.
        prefs.edit().putLong(Constants.PREF_ACTIVE_CONVERSATION_ID, conversationId).apply()
        // Reset API conversation history — we rebuild from DB on send
        conversationHistory.clear()
    }

    private suspend fun selectMostRecentOrCreate() {
        // Pick the most recently updated conversation if one exists, otherwise create.
        val recent = try {
            conversationDao.getAllConversations().first()
        } catch (_: Exception) {
            emptyList()
        }
        val pick = recent.firstOrNull()
        if (pick != null) {
            switchToConversation(pick.id)
        } else {
            startNewConversation()
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            conversationDao.delete(conversationId)
            // If we deleted the active conversation, create a new one
            if (_activeConversationId.value == conversationId) {
                val remaining = conversationDao.getCount()
                if (remaining == 0) {
                    startNewConversation()
                } else {
                    // Switch to most recent
                    // The conversations flow will update, but we need an ID now
                    // Just start fresh
                    startNewConversation()
                }
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            conversationDao.deleteAll()
            conversationHistory.clear()
            startNewConversation()
        }
    }

    fun toggleDrawer() {
        _uiState.value = _uiState.value.copy(isDrawerOpen = !_uiState.value.isDrawerOpen)
    }

    fun closeDrawer() {
        _uiState.value = _uiState.value.copy(isDrawerOpen = false)
    }

    // ── Messaging ──

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            // Create conversation if none active
            var convId = _activeConversationId.value
            if (convId == null) {
                val conversation = ConversationEntity()
                convId = conversationDao.insert(conversation)
                _activeConversationId.value = convId
                _uiState.value = _uiState.value.copy(activeConversationId = convId)
            }

            // Save user message to DB
            messageDao.insertMessage(
                MessageEntity(conversationId = convId, role = "user", content = text)
            )
            conversationDao.touchConversation(convId)

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                streamingText = null
            )

            // Rebuild conversation history from DB if empty (e.g. after switching conversations)
            if (conversationHistory.isEmpty()) {
                rebuildHistory(convId)
            }

            // Check for slash command skills
            val resolvedText = if (text.startsWith("/")) {
                skillToolHandler.resolveSlashCommand(text) ?: text
            } else text

            val result = repository.sendMessage(conversationHistory, resolvedText)

            result.fold(
                onSuccess = { response ->
                    messageDao.insertMessage(
                        MessageEntity(conversationId = convId, role = "assistant", content = response)
                    )
                    conversationDao.touchConversation(convId)
                    _uiState.value = _uiState.value.copy(isLoading = false)

                    // Auto-generate title after first exchange
                    generateTitleIfNeeded(convId)
                },
                onFailure = { error ->
                    messageDao.insertMessage(
                        MessageEntity(conversationId = convId, role = "assistant", content = "Error: ${error.message}")
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message
                    )
                }
            )
        }
    }

    /**
     * Rebuild the in-memory API conversation history from the database.
     * This lets us resume conversations after switching.
     */
    private suspend fun rebuildHistory(conversationId: Long) {
        conversationHistory.clear()
        // We don't store raw API messages, so reconstruct from user/assistant messages
        // Tool status messages are skipped — they're UI-only
        val dbMessages = messages.value.filter { it.role in listOf("user", "assistant") }
        for (msg in dbMessages) {
            conversationHistory.add(Message(role = msg.role, content = msg.content))
        }
    }

    /**
     * Generate a short title from the first user message.
     * Runs only once per conversation (when title is still "New Chat").
     */
    private suspend fun generateTitleIfNeeded(conversationId: Long) {
        val conversation = conversationDao.getConversation(conversationId) ?: return
        if (conversation.title != "New Chat") return

        val firstMessage = messageDao.getFirstUserMessage(conversationId) ?: return
        val title = generateTitle(firstMessage)
        conversationDao.updateTitle(conversationId, title)
    }

    /**
     * Create a concise title from the user's message.
     * Keeps it short and meaningful — no need to call the API for this.
     */
    private fun generateTitle(message: String): String {
        val cleaned = message.trim()
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")

        // If short enough, use it directly
        if (cleaned.length <= 35) return cleaned

        // Try to cut at a natural word boundary
        val truncated = cleaned.take(35)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > 15) {
            truncated.substring(0, lastSpace) + "..."
        } else {
            truncated + "..."
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

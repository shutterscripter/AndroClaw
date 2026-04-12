package com.androclaw.service

import android.content.SharedPreferences
import com.androclaw.api.ClaudeRepository
import com.androclaw.api.models.Message
import com.androclaw.db.ConversationDao
import com.androclaw.db.ConversationEntity
import com.androclaw.db.MessageDao
import com.androclaw.db.MessageEntity
import com.androclaw.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight chat manager for the overlay — no ViewModel dependency.
 * Connects directly to the repository and database, and shares the active
 * conversation id with the main ChatViewModel via SharedPreferences so the
 * overlay always shows the same chat the user has open in the main app.
 */
class OverlayChatManager(
    private val repository: ClaudeRepository,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val prefs: SharedPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val conversationHistory = mutableListOf<Message>()
    private var conversationId: Long? = null
    private var messagesJob: Job? = null

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText

    init {
        // Observe agent events
        scope.launch {
            repository.agentEvents.collect { event ->
                when (event) {
                    is com.androclaw.api.AgentEvent.ToolExecuting -> {
                        _toolStatus.value = event.status.description
                    }
                    is com.androclaw.api.AgentEvent.ToolCompleted -> {
                        _toolStatus.value = null
                    }
                    is com.androclaw.api.AgentEvent.StreamingText -> {
                        _toolStatus.value = null
                        _streamingText.value = (_streamingText.value ?: "") + event.delta
                    }
                    is com.androclaw.api.AgentEvent.FinalResponse -> {
                        _streamingText.value = null
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Pick the conversation to display. Always re-reads the shared pref so that
     * if the user switched chats in the main app since the last overlay open,
     * the overlay follows. Falls back to most recent, then to creating a new one.
     */
    fun ensureConversation() {
        scope.launch {
            val savedId = prefs.getLong(Constants.PREF_ACTIVE_CONVERSATION_ID, -1L)
            val resolved: Long = when {
                savedId > 0L && conversationDao.getConversation(savedId) != null -> savedId
                else -> {
                    val recent = try {
                        conversationDao.getAllConversations().first()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    recent.firstOrNull()?.id ?: conversationDao.insert(ConversationEntity())
                }
            }
            if (conversationId != resolved) {
                conversationId = resolved
                conversationHistory.clear()
                rebuildHistoryFromDb(resolved)
                loadMessages()
                persistActiveId(resolved)
            } else if (messagesJob == null) {
                loadMessages()
            }
        }
    }

    private fun loadMessages() {
        val id = conversationId ?: return
        messagesJob?.cancel()
        messagesJob = scope.launch {
            messageDao.getMessagesForConversation(id).collect {
                _messages.value = it
            }
        }
    }

    private suspend fun rebuildHistoryFromDb(convId: Long) {
        val dbMessages = messageDao.getMessagesForConversation(convId).first()
        conversationHistory.clear()
        for (msg in dbMessages) {
            if (msg.role == "user" || msg.role == "assistant") {
                conversationHistory.add(Message(role = msg.role, content = msg.content))
            }
        }
    }

    private fun persistActiveId(id: Long) {
        prefs.edit().putLong(Constants.PREF_ACTIVE_CONVERSATION_ID, id).apply()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        scope.launch {
            var convId = conversationId
            if (convId == null) {
                val conv = ConversationEntity()
                convId = conversationDao.insert(conv)
                conversationId = convId
                persistActiveId(convId)
                loadMessages()
            }

            messageDao.insertMessage(
                MessageEntity(conversationId = convId, role = "user", content = text)
            )
            conversationDao.touchConversation(convId)

            _isLoading.value = true
            _streamingText.value = null

            val result = repository.sendMessage(conversationHistory, text)
            result.fold(
                onSuccess = { response ->
                    messageDao.insertMessage(
                        MessageEntity(conversationId = convId, role = "assistant", content = response)
                    )
                    conversationDao.touchConversation(convId)

                    // Auto-title
                    val conv = conversationDao.getConversation(convId)
                    if (conv?.title == "New Chat") {
                        val firstMsg = messageDao.getFirstUserMessage(convId)
                        if (firstMsg != null) {
                            val title = if (firstMsg.length <= 35) firstMsg
                            else {
                                val t = firstMsg.take(35)
                                val sp = t.lastIndexOf(' ')
                                if (sp > 15) t.substring(0, sp) + "..." else "$t..."
                            }
                            conversationDao.updateTitle(convId, title)
                        }
                    }
                },
                onFailure = { error ->
                    messageDao.insertMessage(
                        MessageEntity(conversationId = convId, role = "assistant", content = "Error: ${error.message}")
                    )
                }
            )
            _isLoading.value = false
            _streamingText.value = null
        }
    }

    fun startNewChat() {
        scope.launch {
            messagesJob?.cancel()
            messagesJob = null
            _streamingText.value = null
            conversationHistory.clear()
            _messages.value = emptyList()
            val newId = conversationDao.insert(ConversationEntity())
            conversationId = newId
            persistActiveId(newId)
            loadMessages()
        }
    }
}

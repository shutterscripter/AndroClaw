package com.androclaw.ui

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androclaw.api.AgentEvent
import com.androclaw.api.ClaudeRepository
import com.androclaw.api.models.Message
import com.androclaw.db.MessageDao
import com.androclaw.db.MessageEntity
import com.androclaw.utils.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class ChatUiState(
    val isLoading: Boolean = false,
    val currentToolStatus: String? = null,
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ClaudeRepository,
    private val messageDao: MessageDao,
    @Named("encrypted") private val encryptedPrefs: SharedPreferences
) : ViewModel() {

    val messages = messageDao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    // Conversation history for Claude API (in-memory, not persisted as raw API messages)
    private val conversationHistory = mutableListOf<Message>()

    init {
        // Observe agent events
        viewModelScope.launch {
            repository.agentEvents.collect { event ->
                when (event) {
                    is AgentEvent.ToolExecuting -> {
                        _uiState.value = _uiState.value.copy(
                            currentToolStatus = event.status.description
                        )
                        messageDao.insertMessage(
                            MessageEntity(
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
                            currentToolStatus = null
                        )
                    }
                    is AgentEvent.FinalResponse, null -> {}
                }
            }
        }
    }

    fun hasApiKey(): Boolean =
        !encryptedPrefs.getString(Constants.PREF_API_KEY, null).isNullOrBlank()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            // Save user message to DB
            messageDao.insertMessage(
                MessageEntity(role = "user", content = text)
            )

            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = repository.sendMessage(conversationHistory, text)

            result.fold(
                onSuccess = { response ->
                    messageDao.insertMessage(
                        MessageEntity(role = "assistant", content = response)
                    )
                    _uiState.value = _uiState.value.copy(isLoading = false)
                },
                onFailure = { error ->
                    messageDao.insertMessage(
                        MessageEntity(role = "assistant", content = "Error: ${error.message}")
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message
                    )
                }
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            messageDao.clearAll()
            conversationHistory.clear()
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

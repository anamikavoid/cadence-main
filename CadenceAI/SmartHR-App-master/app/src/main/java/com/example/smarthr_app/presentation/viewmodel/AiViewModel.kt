package com.example.smarthr_app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarthr_app.data.model.AiChatResponse
import com.example.smarthr_app.data.model.AiPendingActionDto
import com.example.smarthr_app.data.repository.AiRepository
import com.example.smarthr_app.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One chat bubble on screen. [status] is the server's status for assistant replies (e.g. COMPLETED). */
data class AiChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false,
    val status: String? = null
)

/** Everything the chat screen needs to draw itself. The screen only READS this; it never changes it. */
data class AiUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    /** Non-null while the assistant waits for the user to confirm an action (shows Confirm/Cancel buttons). */
    val pendingAction: AiPendingActionDto? = null
)

/**
 * VIEWMODEL = holds the chat screen's state and starts the work. It survives screen rotation.
 * Flow: screen calls sendMessage() -> ViewModel shows the user's bubble and a loading indicator ->
 * AiRepository calls the backend -> ViewModel publishes the new [AiUiState] -> Compose redraws.
 *
 * It contains no UI code and no networking code, just state and decisions.
 */
class AiViewModel(private val repository: AiRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState

    private var nextId = 0L
    private fun newId(): Long = nextId++

    /**
     * Loads the saved conversation from the SERVER. The screen calls this every time it opens, so
     * what you see always belongs to the user who is logged in now, even if somebody else used
     * this phone before (the old state is wiped first).
     */
    fun loadHistory() {
        viewModelScope.launch {
            _uiState.value = AiUiState(isLoading = true)
            when (val result = repository.loadHistory()) {
                is Resource.Success -> {
                    val history = result.data
                    _uiState.value = AiUiState(
                        messages = (history.messages ?: emptyList()).map {
                            AiChatMessage(id = newId(), text = it.text, fromUser = it.role == "USER")
                        },
                        pendingAction = history.pendingAction
                    )
                }
                is Resource.Error -> {
                    _uiState.value = AiUiState(
                        messages = listOf(AiChatMessage(id = newId(), text = result.message, fromUser = false, isError = true))
                    )
                }
                is Resource.Loading -> Unit
            }
        }
    }

    /** The user pressed Send (or tapped an example). */
    fun sendMessage(text: String) {
        val message = text.trim()
        val current = _uiState.value
        if (message.isEmpty() || current.isLoading) return // ignore empty input and double taps

        // Show the user's bubble immediately. Typing a new message also dismisses any open proposal
        // (the server does the same).
        _uiState.value = current.copy(
            messages = current.messages + AiChatMessage(id = newId(), text = message, fromUser = true),
            isLoading = true,
            pendingAction = null
        )
        viewModelScope.launch {
            handleResult(repository.sendMessage(message))
        }
    }

    /** The user tapped Confirm ([accept] = true) or Cancel on a proposed action. */
    fun respondToPending(accept: Boolean) {
        val current = _uiState.value
        val pending = current.pendingAction ?: return
        if (current.isLoading) return

        _uiState.value = current.copy(
            messages = current.messages + AiChatMessage(id = newId(), text = if (accept) "Confirm" else "Cancel", fromUser = true),
            isLoading = true,
            pendingAction = null
        )
        viewModelScope.launch {
            // Only the id is sent. WHAT happens is whatever the server stored when it proposed the action.
            handleResult(repository.confirm(pending.id, accept))
        }
    }

    /** Deletes the saved conversation on the server (the user's events are not affected). */
    fun clearConversation() {
        viewModelScope.launch {
            when (val result = repository.clearConversation()) {
                is Resource.Success -> _uiState.value = AiUiState()
                is Resource.Error -> appendError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }

    /** Forget everything held in memory (called on logout so the next user starts clean). */
    fun reset() {
        _uiState.value = AiUiState()
    }

    private fun handleResult(result: Resource<AiChatResponse>) {
        when (result) {
            is Resource.Success -> {
                val response = result.data
                val s = _uiState.value
                _uiState.value = s.copy(
                    messages = s.messages + AiChatMessage(id = newId(), text = response.reply, fromUser = false, status = response.status),
                    isLoading = false,
                    pendingAction = response.pendingAction
                )
            }
            is Resource.Error -> appendError(result.message)
            is Resource.Loading -> Unit
        }
    }

    private fun appendError(message: String) {
        val s = _uiState.value
        _uiState.value = s.copy(
            messages = s.messages + AiChatMessage(id = newId(), text = message, fromUser = false, isError = true),
            isLoading = false,
            pendingAction = null
        )
    }
}

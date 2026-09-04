package com.example.nospam.feature.thread

import androidx.lifecycle.ViewModel
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.MessageId
import com.example.nospam.core.model.MessageType
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThreadUiState(
    val threadId: Long,
    val messages: List<Message> = emptyList(),
    val draft: String = ""
)

class ThreadViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ThreadUiState(threadId = 0, messages = fakeMessages()))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    fun loadThread(id: Long) {
        _uiState.value = ThreadUiState(threadId = id, messages = fakeMessages())
    }

    fun onDraftChanged(text: String) {
        _uiState.value = _uiState.value.copy(draft = text)
    }

    fun onSend() {
        val current = _uiState.value
        if (current.draft.isBlank()) return
        val newMsg = Message(
            id = MessageId(System.currentTimeMillis()),
            threadId = ThreadId(current.threadId),
            address = "me",
            body = current.draft,
            date = System.currentTimeMillis(),
            type = MessageType.SENT,
            read = true
        )
        _uiState.value = current.copy(messages = current.messages + newMsg, draft = "")
    }

    private fun fakeMessages(): List<Message> = listOf(
        Message(MessageId(1), ThreadId(1), "Alice", "Hey! Are we still on for lunch later? 🥗", System.currentTimeMillis() - 1000*60*20, MessageType.INBOX, true),
        Message(MessageId(2), ThreadId(1), "me", "Absolutely! I was thinking that new Thai place downtown?", System.currentTimeMillis() - 1000*60*15, MessageType.SENT, true),
        Message(MessageId(3), ThreadId(1), "Alice", "Perfect! I love Thai food.", System.currentTimeMillis() - 1000*60*10, MessageType.INBOX, true),
        Message(MessageId(4), ThreadId(1), "Alice", "Meet you there at 12:30?", System.currentTimeMillis() - 1000*60*5, MessageType.INBOX, true),
    )
}

package com.example.nospam.feature.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.MessageId
import com.example.nospam.core.model.MessageType
import com.example.nospam.core.model.ThreadId
import com.example.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThreadUiState(
    val threadId: Long,
    val messages: List<Message> = emptyList(),
    val draft: String = ""
)

/**
 * @param dataSource when null (previews, unit tests), serves the fake thread
 * and appends sent messages locally. When provided, messages come from the
 * system provider and sending goes through SmsManager + sent-box write.
 */
class ThreadViewModel(
    private val dataSource: TelephonyDataSource? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ThreadUiState(threadId = 0, messages = fakeMessages()))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    fun loadThread(id: Long) {
        val dataSource = this.dataSource
        if (dataSource == null) {
            _uiState.value = ThreadUiState(threadId = id, messages = fakeMessages())
            return
        }
        _uiState.value = _uiState.value.copy(threadId = id, messages = emptyList())
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                threadId = id,
                messages = dataSource.getMessages(ThreadId(id)),
            )
        }
    }

    fun onDraftChanged(text: String) {
        _uiState.value = _uiState.value.copy(draft = text)
    }

    fun onSend() {
        val current = _uiState.value
        if (current.draft.isBlank()) return
        val dataSource = this.dataSource
        if (dataSource == null) {
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
            return
        }
        // Address = the other party: first incoming message's sender.
        val address = current.messages.firstOrNull { it.type == MessageType.INBOX }?.address
            ?: return
        val body = current.draft
        _uiState.value = current.copy(draft = "")
        viewModelScope.launch {
            dataSource.sendMessage(address, body, subscriptionId = null)
            dataSource.insertSentMessage(address, body, System.currentTimeMillis(), subscriptionId = null)
            _uiState.value = _uiState.value.copy(messages = dataSource.getMessages(ThreadId(current.threadId)))
        }
    }

    private fun fakeMessages(): List<Message> = listOf(
        Message(MessageId(1), ThreadId(1), "Alice", "Hey! Are we still on for lunch later? 🥗", System.currentTimeMillis() - 1000*60*20, MessageType.INBOX, true),
        Message(MessageId(2), ThreadId(1), "me", "Absolutely! I was thinking that new Thai place downtown?", System.currentTimeMillis() - 1000*60*15, MessageType.SENT, true),
        Message(MessageId(3), ThreadId(1), "Alice", "Perfect! I love Thai food.", System.currentTimeMillis() - 1000*60*10, MessageType.INBOX, true),
        Message(MessageId(4), ThreadId(1), "Alice", "Meet you there at 12:30?", System.currentTimeMillis() - 1000*60*5, MessageType.INBOX, true),
    )
}

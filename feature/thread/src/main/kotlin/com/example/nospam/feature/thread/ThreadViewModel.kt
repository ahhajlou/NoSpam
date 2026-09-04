package com.example.nospam.feature.thread

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.MessageId
import com.example.nospam.core.model.MessageType
import com.example.nospam.core.model.ThreadId
import com.example.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class ThreadUiState(
    val threadId: Long,
    val messages: List<Message> = emptyList(),
    val draft: String = ""
)

/**
 * @param dataSource when null (previews, unit tests), serves the fake thread
 * and appends sent messages locally. When provided, messages stream from the
 * system provider via [TelephonyDataSource.observeMessages], so incoming SMS
 * appear without leaving the screen; sending goes through SmsManager +
 * sent-box write with an optimistic row for instant feedback.
 */
class ThreadViewModel(
    private val dataSource: TelephonyDataSource? = null,
    initialAddress: String? = null,
) : ViewModel() {
    // The other party for threads reached from New Conversation, which have
    // no messages yet. Mutable because one VM instance can serve successive
    // ThreadRoutes (same navigation scope).
    private var pendingAddress: String? = initialAddress

    private val _uiState = MutableStateFlow(ThreadUiState(threadId = 0, messages = fakeMessages()))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var lastRemote: List<Message> = emptyList()
    // Optimistic rows (negative ids) not yet confirmed by the provider.
    private var optimistic: List<Message> = emptyList()

    companion object {
        private const val TAG = "ThreadViewModel"
    }

    fun loadThread(id: Long, address: String? = null) {
        if (address != null) pendingAddress = address
        val dataSource = this.dataSource
        if (dataSource == null) {
            _uiState.value = ThreadUiState(threadId = id, messages = fakeMessages())
            return
        }
        optimistic = emptyList()
        lastRemote = emptyList()
        _uiState.value = _uiState.value.copy(threadId = id, messages = emptyList())
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            dataSource.observeMessages(ThreadId(id)).collect { remote ->
                lastRemote = remote
                if (remote.any { !it.read }) {
                    // Terminates: the update re-emits with everything read.
                    dataSource.markAsRead(ThreadId(id))
                }
                _uiState.value = _uiState.value.copy(threadId = id, messages = merged())
            }
        }
    }

    /** Provider rows win; unconfirmed optimistic rows are kept. */
    private fun merged(): List<Message> {
        val confirmed = lastRemote
            .filter { it.type == MessageType.SENT }
            .map { it.body to it.address }
            .toSet()
        optimistic = optimistic.filterNot { (it.body to it.address) in confirmed }
        return (lastRemote + optimistic).sortedBy { it.date }
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
        // Address = the other party: first incoming message's sender,
        // falling back to the address the thread was opened with (new threads
        // reached from New Conversation have no messages yet).
        val address = current.messages.firstOrNull { it.type == MessageType.INBOX }?.address
            ?: pendingAddress
            ?: return
        val body = current.draft
        // Negative ids never collide with provider row ids.
        optimistic = optimistic + Message(
            id = MessageId(-System.currentTimeMillis()),
            threadId = ThreadId(current.threadId),
            address = address,
            body = body,
            date = System.currentTimeMillis(),
            type = MessageType.SENT,
            read = true,
        )
        _uiState.value = current.copy(draft = "", messages = merged())
        viewModelScope.launch {
            val sendResult = dataSource.sendMessage(address, body, subscriptionId = null)
            if (sendResult.isFailure) {
                Log.w(TAG, "SmsManager send failed", sendResult.exceptionOrNull())
            }
            val rowId = dataSource.insertSentMessage(
                address, body, System.currentTimeMillis(), subscriptionId = null
            )
            if (rowId == null) Log.w(TAG, "insertSentMessage failed")
            // No manual reload: the provider observer re-emits and reconciles.
        }
    }

    private fun fakeMessages(): List<Message> = listOf(
        Message(MessageId(1), ThreadId(1), "Alice", "Hey! Are we still on for lunch later? 🥗", System.currentTimeMillis() - 1000*60*20, MessageType.INBOX, true),
        Message(MessageId(2), ThreadId(1), "me", "Absolutely! I was thinking that new Thai place downtown?", System.currentTimeMillis() - 1000*60*15, MessageType.SENT, true),
        Message(MessageId(3), ThreadId(1), "Alice", "Perfect! I love Thai food.", System.currentTimeMillis() - 1000*60*10, MessageType.INBOX, true),
        Message(MessageId(4), ThreadId(1), "Alice", "Meet you there at 12:30?", System.currentTimeMillis() - 1000*60*5, MessageType.INBOX, true),
    )
}

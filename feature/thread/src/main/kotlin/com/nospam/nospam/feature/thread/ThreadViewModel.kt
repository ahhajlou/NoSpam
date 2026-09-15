package com.nospam.nospam.feature.thread

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThreadUiState(
    val threadId: Long,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val spamMessageIds: Set<Long> = emptySet(),
    val onMarkNotSpam: ((Long) -> Unit)? = null,
    val onReportSpam: ((Long) -> Unit)? = null,
    val sims: List<TelephonyDataSource.SimInfo> = emptyList(),
    val selectedSimId: Int? = null,
    /** Oldest loaded message(s) still on disk — the UI shows a scroll-to-load hint. */
    val hasMoreOlder: Boolean = false,
    val loadingOlder: Boolean = false,
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
    private val spamRepository: SpamRepository? = null,
) : ViewModel() {
    // The other party for threads reached from New Conversation, which have
    // no messages yet. Mutable because one VM instance can serve successive
    // ThreadRoutes (same navigation scope).
    private var pendingAddress: String? = initialAddress
    private var lastContext: android.content.Context? = null

    private val _uiState = MutableStateFlow(ThreadUiState(threadId = 0, messages = fakeMessages()))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var verdictsJob: Job? = null
    private var lastRemote: List<Message> = emptyList()
    // Optimistic rows (negative ids) not yet confirmed by the provider.
    private var optimistic: List<Message> = emptyList()
    // Older pages accumulated by backward pagination (see [loadOlder]).
    private var olderMessages: List<Message> = emptyList()
    private var hasOlder = false
    private var loadingOlder = false

    companion object {
        private const val TAG = "ThreadViewModel"
    }

    fun loadThread(id: Long, address: String? = null, context: android.content.Context? = null, forwardBody: String? = null) {
        if (address != null) pendingAddress = address
        if (context != null) lastContext = context.applicationContext
        // Cancel notification for this thread when user opens it (no core:notifications dep)
        context?.let { ctx ->
            runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(id.toInt()) }
        }
        // A forwarded message's body wins over any previously-saved draft for
        // this thread; skip the DataStore load so it doesn't get overwritten.
        if (forwardBody != null) {
            _uiState.value = _uiState.value.copy(draft = forwardBody)
        } else if (context != null) {
            viewModelScope.launch {
                val draft = runCatching { DraftStore.load(context, id) }.getOrNull()
                if (draft != null) _uiState.value = _uiState.value.copy(draft = draft)
            }
        }
        if (context != null) {
            // Load SIMs for dual-SIM picker
            viewModelScope.launch {
                val sims = dataSource?.getActiveSubscriptions() ?: emptyList()
                _uiState.value = _uiState.value.copy(sims = sims, selectedSimId = sims.firstOrNull()?.subscriptionId)
            }
        }
        val dataSource = this.dataSource
        if (dataSource == null) {
            _uiState.value = ThreadUiState(threadId = id, messages = fakeMessages())
            return
        }
        optimistic = emptyList()
        lastRemote = emptyList()
        olderMessages = emptyList()
        hasOlder = false
        loadingOlder = false
        _uiState.value = _uiState.value.copy(
            threadId = id, messages = emptyList(), spamMessageIds = emptySet(),
            hasMoreOlder = false, loadingOlder = false,
        )
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            dataSource.observeMessages(ThreadId(id)).collect { remote ->
                lastRemote = remote
                // The newest page fills a full page => older rows exist on disk.
                if (remote.size >= TelephonyDataSource.MESSAGES_PAGE_SIZE) hasOlder = true
                if (remote.any { !it.read }) {
                    // Terminates: the update re-emits with everything read.
                    dataSource.markAsRead(ThreadId(id))
                }
                _uiState.value = _uiState.value.copy(threadId = id, messages = merged(), hasMoreOlder = hasOlder)
            }
        }
        // Per-message "Not spam"/"Report spam" inside a MIXED thread (no sender override).
        spamRepository?.let { repo ->
            _uiState.value = _uiState.value.copy(
                onMarkNotSpam = { msgId -> viewModelScope.launch { repo.markMessageNotSpam(msgId) } },
                onReportSpam = { msgId -> viewModelScope.launch { repo.markMessageSpam(msgId) } },
            )
            verdictsJob?.cancel()
            verdictsJob = viewModelScope.launch {
                repo.observeThreadSpamMessageIds(id).collect { ids ->
                    _uiState.value = _uiState.value.copy(spamMessageIds = ids)
                }
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
        // The provider emits only the newest page; drop any older-page row that
        // the sliding window has caught up with (re-emit after a new message).
        lastRemote.minOfOrNull { it.id.value }?.let { newestPageMinId ->
            if (olderMessages.isNotEmpty()) {
                olderMessages = olderMessages.filter { it.id.value < newestPageMinId }
            }
        }
        return (olderMessages + lastRemote + optimistic).sortedWith(compareBy({ it.date }, { it.id.value }))
    }

    /**
     * Prepend the next older page. Guarded against re-entry; no-op while a page
     * is in flight or once the oldest row has been reached.
     */
    fun loadOlder() {
        if (loadingOlder || !hasOlder) return
        val threadId = _uiState.value.threadId
        if (threadId == 0L) return
        val beforeId = (olderMessages + lastRemote).minOfOrNull { it.id.value } ?: return
        loadingOlder = true
        _uiState.value = _uiState.value.copy(loadingOlder = true)
        viewModelScope.launch {
            val page = runCatching {
                dataSource?.getMessages(ThreadId(threadId), TelephonyDataSource.MESSAGES_PAGE_SIZE, beforeId)
            }.getOrNull().orEmpty()
            olderMessages = olderMessages + page
            hasOlder = page.size >= TelephonyDataSource.MESSAGES_PAGE_SIZE
            loadingOlder = false
            _uiState.value = _uiState.value.copy(
                messages = merged(),
                loadingOlder = false,
                hasMoreOlder = hasOlder,
            )
        }
    }

    fun onDraftChanged(text: String) {
        _uiState.value = _uiState.value.copy(draft = text)
        lastContext?.let { ctx ->
            val id = _uiState.value.threadId
            viewModelScope.launch { runCatching { DraftStore.save(ctx, id, text) } }
        }
    }

    fun onSimSelected(subId: Int) {
        _uiState.value = _uiState.value.copy(selectedSimId = subId)
    }

    fun onDeleteMessage(messageId: Long) {
        val dataSource = this.dataSource
        if (dataSource == null) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.filterNot { it.id.value == messageId }
            )
            return
        }
        // No manual reload: the provider observer re-emits and reconciles.
        viewModelScope.launch { dataSource.deleteMessage(messageId) }
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
        val selectedSim = current.selectedSimId
        viewModelScope.launch {
            val sendResult = dataSource.sendMessage(address, body, subscriptionId = selectedSim)
            if (sendResult.isFailure) {
                Log.w(TAG, "SmsManager send failed", sendResult.exceptionOrNull())
            }
            val rowId = dataSource.insertSentMessage(
                address, body, System.currentTimeMillis(), subscriptionId = selectedSim
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

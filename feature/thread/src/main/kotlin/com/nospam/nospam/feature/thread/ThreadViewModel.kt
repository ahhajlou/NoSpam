// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.DraftRepository
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.SenderState
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.model.isAlphanumericSender
import com.nospam.nospam.core.model.isOutgoing
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer
import com.nospam.nospam.core.telephony.SendOptions
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThreadUiState(
    val threadId: Long,
    val messages: List<Message> = emptyList(),
    /** The other party's address, once known from the route or a message. */
    val address: String? = null,
    /** Contact name for [address], when the address is in the user's contacts. */
    val contactName: String? = null,
    /** That contact's photo, when they have one. */
    val contactPhotoUri: String? = null,
    val draft: String = "",
    val spamMessageIds: Set<Long> = emptySet(),
    val onMarkNotSpam: ((Long) -> Unit)? = null,
    val onReportSpam: ((Long) -> Unit)? = null,
    val sims: List<TelephonyDataSource.SimInfo> = emptyList(),
    val selectedSimId: Int? = null,
    /** Oldest loaded message(s) still on disk — the UI shows a scroll-to-load hint. */
    val hasMoreOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** Why this conversation is flagged, shown above the messages with a "Not spam" action. */
    val spamBanner: SpamBanner? = null,
    /**
     * True until the provider's first answer for this thread. The screen draws
     * no messages and no empty state meanwhile, so opening a conversation never
     * shows anything but that conversation.
     */
    val isLoading: Boolean = true,
    /** The links, phone numbers and email addresses in each message, by message id. */
    val links: Map<Long, List<MessageLink>> = emptyMap(),
    /**
     * Whether links in received messages can be tapped. Not before the contact
     * and spam lookups answer, so a link never shows on a message about to be
     * labeled spam, and never for a sender in Spam or blocked. A message labeled
     * suspected spam ([spamMessageIds]) keeps them as text in any case.
     */
    val incomingLinks: Boolean = false,
) {
    /** False for an alphanumeric sender ID, which a reply cannot reach. */
    val canReply: Boolean get() = address?.let { !isAlphanumericSender(it) } ?: true
}

/** The banner at the top of a flagged conversation. */
enum class SpamBanner {
    /** In the inbox, with messages the on-device filter flagged. */
    SUSPECTED_MESSAGES,

    /** Moved to Spam by the on-device filter, not by the user. */
    IN_SPAM,
}

/**
 * @param dataSource when null (previews, unit tests), serves the fake thread
 * and appends sent messages locally. When provided, messages stream from the
 * system provider via [TelephonyDataSource.observeMessages], so incoming SMS
 * appear without leaving the screen; sending goes through SmsManager +
 * sent-box write with an optimistic row for instant feedback.
 * @param drafts when null (previews, tests that do not care), drafts are not
 * persisted.
 * @param settings when null, the SIM picker shows the carrier's numbers only,
 * not ones the user entered.
 */
class ThreadViewModel(
    private val dataSource: TelephonyDataSource? = null,
    initialAddress: String? = null,
    private val spamRepository: SpamRepository? = null,
    private val drafts: DraftRepository? = null,
    private val settings: SettingsRepository? = null,
    /** Called once a message the user sent was handed to the radio: the sent sound. */
    private val onMessageQueued: suspend () -> Unit = {},
    /** Where links are looked for; a test passes its own to control when that runs. */
    private val linkDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    // The other party for threads reached from New Conversation, which have
    // no messages yet. Mutable because one VM instance can serve successive
    // ThreadRoutes (same navigation scope).
    private var pendingAddress: String? = initialAddress
    private var simPickedByUser = false
    // The system's default SMS SIM, read with the SIM list.
    private var defaultSimId: Int? = null

    // Starts empty and loading. It used to start with the preview conversation,
    // which every open drew for a frame or more while the screen faded in.
    private val _uiState = MutableStateFlow(ThreadUiState(threadId = 0))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var contactJob: Job? = null
    private var verdictsJob: Job? = null
    /** Message ids the verdict store flags in this thread, before the contact check. */
    private var threadSpamIds: Set<Long> = emptySet()
    private var senderState: SenderState? = null
    // Whether the contact lookup for the other party has answered. Labels and
    // the banner wait for it: a contact's conversation carries neither, and
    // showing them until the lookup returns made them flash.
    private var contactChecked = false
    private var senderJob: Job? = null
    private var observedSender: String? = null
    // Whether the sender's state and the thread's verdicts have answered once:
    // links in received messages wait for both (see [ThreadUiState.incomingLinks]).
    private var senderChecked = false
    private var verdictsChecked = false
    private var linksJob: Job? = null
    /** The country numbers without "+" in message text are read in; null until known. */
    private val numberRegion = MutableStateFlow<String?>(null)
    private var linksRegion: String? = null
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

    /**
     * @param contactName and [contactPhotoUri] what the list the user came from
     * already knew about the other party, shown at once instead of the number
     * while the lookup runs. The lookup still runs and wins.
     */
    fun loadThread(
        id: Long,
        address: String? = null,
        context: android.content.Context? = null,
        forwardBody: String? = null,
        contactName: String? = null,
        contactPhotoUri: String? = null,
    ) {
        if (address != null) pendingAddress = address
        startLinks(context)
        // A forwarded message's body wins over any previously-saved draft for
        // this thread; skip the saved-draft load so it doesn't get overwritten.
        val drafts = this.drafts
        if (forwardBody != null) {
            _uiState.value = _uiState.value.copy(draft = forwardBody)
        } else if (drafts != null) {
            viewModelScope.launch {
                val draft = drafts.load(id)
                // Never overwrite text the user has already started typing.
                if (draft != null && _uiState.value.draft.isEmpty()) {
                    _uiState.value = _uiState.value.copy(draft = draft)
                }
            }
        }
        if (context != null) {
            // Load SIMs for dual-SIM picker
            viewModelScope.launch {
                val carrier = dataSource?.getActiveSubscriptions() ?: emptyList()
                val entered = settings?.simNumbers?.first().orEmpty()
                val sims = carrier.map { sim -> entered[sim.subscriptionId]?.let { sim.copy(number = it) } ?: sim }
                defaultSimId = runCatching { dataSource?.getDefaultSmsSubscriptionId() }.getOrNull()
                _uiState.value = _uiState.value.copy(sims = sims)
                syncSelectedSim()
            }
        }
        val dataSource = this.dataSource
        if (dataSource == null) {
            _uiState.value = ThreadUiState(
                threadId = id,
                messages = fakeMessages(),
                draft = forwardBody ?: "",
                // A contact, so previews show the name with the number beneath it.
                address = address ?: "+15550101",
                contactName = "Alice",
                isLoading = false,
                incomingLinks = true,
            )
            return
        }
        contactJob?.cancel()
        optimistic = emptyList()
        lastRemote = emptyList()
        olderMessages = emptyList()
        hasOlder = false
        loadingOlder = false
        _uiState.value = _uiState.value.copy(
            threadId = id, messages = emptyList(), spamMessageIds = emptySet(),
            hasMoreOlder = false, loadingOlder = false,
            address = pendingAddress, contactName = contactName, contactPhotoUri = contactPhotoUri, spamBanner = null,
            isLoading = true, incomingLinks = false,
        )
        // A name from the list came from the same contact lookup.
        contactChecked = contactName != null
        pendingAddress?.let(::resolveContact)
        threadSpamIds = emptySet()
        senderState = null
        senderJob?.cancel()
        observedSender = null
        senderChecked = false
        verdictsChecked = false
        pendingAddress?.let(::observeSender)
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
                // The route carries an address only when the thread was opened
                // from the recipient picker; otherwise the other party is the
                // sender of the first incoming message.
                val other = _uiState.value.address ?: otherParty(remote)
                if (other != null && _uiState.value.address == null) resolveContact(other)
                other?.let(::observeSender)
                _uiState.value = _uiState.value.copy(
                    threadId = id, messages = merged(), hasMoreOlder = hasOlder, address = other, isLoading = false,
                )
                // Nobody to look up (no messages yet, no address): nothing to wait for.
                if (other == null && !contactChecked) {
                    contactChecked = true
                    publishSpamIds()
                }
                syncSelectedSim()
            }
        }
        // Per-message "Not spam"/"Report spam" on messages labeled suspected spam.
        spamRepository?.let { repo ->
            _uiState.value = _uiState.value.copy(
                onMarkNotSpam = { msgId -> viewModelScope.launch { repo.markMessageNotSpam(msgId) } },
                onReportSpam = { msgId -> viewModelScope.launch { repo.markMessageSpam(msgId) } },
            )
            verdictsJob?.cancel()
            verdictsJob = viewModelScope.launch {
                repo.observeThreadSpamMessageIds(id).collect { ids ->
                    threadSpamIds = ids
                    verdictsChecked = true
                    publishSpamIds()
                }
            }
        }
    }

    /**
     * Contact name and photo for the title. A miss (unknown number, no
     * permission) leaves both null and the screen falls back to the address and
     * a letter avatar.
     */
    private fun resolveContact(address: String) {
        val dataSource = this.dataSource ?: return
        contactJob?.cancel()
        contactJob = viewModelScope.launch {
            val contact = runCatching { dataSource.lookupContact(address) }.getOrNull()
            if (contact?.displayName != null) {
                _uiState.value = _uiState.value.copy(
                    contactName = contact.displayName,
                    contactPhotoUri = contact.photoUri,
                )
            }
            contactChecked = true
            publishSpamIds()
        }
    }

    /** Follows what the app knows about the other party, for the banner. */
    private fun observeSender(address: String) {
        val repo = spamRepository ?: return
        if (observedSender == address) return
        observedSender = address
        senderJob?.cancel()
        senderJob = viewModelScope.launch {
            repo.observeSenderState(address).collect {
                senderState = it
                senderChecked = true
                publishSpamIds()
            }
        }
    }

    /**
     * Labels and banner. Saved contacts bypass the classifier, and a sender the
     * user marked "Not spam" has been decided about, so neither carries a
     * "Suspected spam" label, including verdicts stored before. A conversation
     * the user reported or blocked gets no banner: the user put it there.
     */
    private fun publishSpamIds() {
        if (!contactChecked) return
        val trusted = senderState?.state == ThreadSpamState.TRUSTED
        val ids = if (_uiState.value.contactName != null || trusted) emptySet() else threadSpamIds
        val sender = senderState
        val banner = when {
            _uiState.value.contactName != null || trusted -> null
            sender?.isUserOverride == true || sender?.state == ThreadSpamState.BLOCKED -> null
            sender?.state == ThreadSpamState.SPAM -> SpamBanner.IN_SPAM
            ids.isNotEmpty() -> SpamBanner.SUSPECTED_MESSAGES
            else -> null
        }
        // The user's own decisions hold for a saved contact too; an automatic
        // state recorded before the sender was saved does not.
        val senderInSpam = when (sender?.state) {
            ThreadSpamState.BLOCKED -> true
            ThreadSpamState.SPAM -> sender?.isUserOverride == true || _uiState.value.contactName == null
            else -> false
        }
        val checked = spamRepository == null || observedSender == null || (senderChecked && verdictsChecked)
        _uiState.value = _uiState.value.copy(
            spamMessageIds = ids, spamBanner = banner, incomingLinks = checked && !senderInSpam,
        )
    }

    /**
     * Finds the links in each message, off the main thread: the first search
     * loads libphonenumber's metadata (45 ms on a desktop JVM), and each message
     * then costs tens of microseconds, too much to repeat as bubbles scroll in.
     * A message is searched once per region, since a body never changes under
     * its id. Reading the region takes a context, so without one (unit tests of
     * everything else) no links are looked for.
     */
    private fun startLinks(context: android.content.Context?) {
        val app = context?.applicationContext ?: return
        if (numberRegion.value == null) {
            viewModelScope.launch {
                numberRegion.value = withContext(linkDispatcher) { PhoneNumberNormalizer.region(app) }
            }
        }
        if (linksJob != null) return
        linksJob = viewModelScope.launch {
            combine(_uiState.map { it.messages }.distinctUntilChanged(), numberRegion.filterNotNull(), ::Pair)
                .collectLatest { (messages, region) ->
                    val known = if (region == linksRegion) _uiState.value.links else emptyMap()
                    val links = withContext(linkDispatcher) {
                        messages.associate { it.id.value to (known[it.id.value] ?: findMessageLinks(it.body, region)) }
                    }
                    linksRegion = region
                    _uiState.value = _uiState.value.copy(links = links)
                }
        }
    }

    /**
     * The banner's "Not spam": the user's decision for the whole sender, which
     * returns the conversation to the inbox and clears its labels.
     */
    fun onBannerNotSpam() {
        val repo = spamRepository ?: return
        val address = _uiState.value.address ?: return
        val threadId = _uiState.value.threadId
        viewModelScope.launch { repo.markSenderNotSpam(ThreadId(threadId), address) }
    }

    /**
     * The other party: the first sender of an incoming message, else the
     * recipient of a sent one. A thread holding only outgoing messages (sent to
     * a number that never replied) has no incoming row to read it from.
     */
    private fun otherParty(messages: List<Message>): String? =
        messages.firstOrNull { it.type == MessageType.INBOX }?.address
            ?: messages.firstOrNull { it.type.isOutgoing }?.address

    /** Provider rows win; unconfirmed optimistic rows are kept. */
    private fun merged(): List<Message> {
        val confirmed = lastRemote
            .filter { it.type.isOutgoing }
            .map { it.body to it.address }
            .toSet()
        optimistic = optimistic.filterNot { (it.body to it.address) in confirmed }
        // The provider emits only the newest page; drop any older-page row that
        // the sliding window has caught up with (re-emit after a new message).
        // By id, not by position: ids need not follow dates.
        if (olderMessages.isNotEmpty()) {
            val inWindow = lastRemote.map { it.id.value }.toSet()
            olderMessages = olderMessages.filterNot { it.id.value in inWindow }
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
        // The oldest loaded message by the provider's own sort key (date, id).
        val oldest = (olderMessages + lastRemote).minWithOrNull(compareBy({ it.date }, { it.id.value })) ?: return
        loadingOlder = true
        _uiState.value = _uiState.value.copy(loadingOlder = true)
        viewModelScope.launch {
            val page = runCatching {
                dataSource?.getMessages(ThreadId(threadId), TelephonyDataSource.MESSAGES_PAGE_SIZE, oldest)
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
        drafts?.let { store ->
            val id = _uiState.value.threadId
            viewModelScope.launch { store.save(id, text) }
        }
    }

    fun onSimSelected(subId: Int) {
        simPickedByUser = true
        _uiState.value = _uiState.value.copy(selectedSimId = subId)
    }

    /**
     * Replies go out on the SIM the conversation last used, as the provider
     * recorded it, not always on the first SIM. Falls back to the first SIM when
     * the thread has no history on an active one. Never overrides the user's pick.
     */
    /**
     * Which SIM a reply goes out on, unless the user picked one: the SIM this
     * thread last used, else the one already selected, else the system's
     * default SMS SIM, else the first. The default matters for a conversation
     * with no history, which used to start on the first SIM whatever the
     * phone's setting was.
     */
    private fun syncSelectedSim() {
        if (simPickedByUser) return
        val state = _uiState.value
        if (state.sims.isEmpty()) return
        val active = state.sims.map { it.subscriptionId }.toSet()
        val threadSim = state.messages
            .lastOrNull { it.subscriptionId != null && it.subscriptionId in active && it.id.value > 0 }
            ?.subscriptionId
        val chosen = threadSim
            ?: state.selectedSimId?.takeIf { it in active }
            ?: defaultSimId?.takeIf { it in active }
            ?: state.sims.first().subscriptionId
        if (chosen != state.selectedSimId) _uiState.value = state.copy(selectedSimId = chosen)
    }

    fun onDeleteMessages(messageIds: Collection<Long>) = messageIds.forEach(::onDeleteMessage)

    fun onDeleteMessage(messageId: Long) {
        val dataSource = this.dataSource
        if (dataSource == null) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.filterNot { it.id.value == messageId }
            )
            return
        }
        // No manual reload: the provider observer re-emits and reconciles.
        viewModelScope.launch {
            dataSource.deleteMessage(messageId)
            // Its verdict goes too, so a deleted spam message no longer labels
            // the conversation in the inbox. The sender's counts are kept.
            runCatching { spamRepository?.onMessageDeleted(messageId) }
        }
    }

    fun onSend() {
        val current = _uiState.value
        if (current.draft.isBlank() || !current.canReply) return
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
        val address = otherParty(current.messages) ?: pendingAddress ?: return
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
        // The persisted draft is the text just sent; leaving it would bring the
        // sent message back as a draft the next time the thread opens.
        drafts?.let { store ->
            viewModelScope.launch { store.save(current.threadId, "") }
        }
        val selectedSim = current.selectedSimId
        viewModelScope.launch { deliver(address, body, selectedSim, existingId = null) }
    }

    /**
     * Writes the message as OUTBOX, then sends it. The provider row is what the
     * user sees from then on: it turns SENT or FAILED when the radio reports,
     * so a message that did not go out says so instead of looking sent.
     */
    private suspend fun deliver(address: String, body: String, sim: Int?, existingId: Long?) {
        val dataSource = this.dataSource ?: return
        val rowId = existingId ?: dataSource.insertOutboxMessage(address, body, System.currentTimeMillis(), sim)
        val reports = sim != null && settings?.deliveryReportSims?.first()?.contains(sim) == true
        val result = dataSource.sendMessage(
            address, body, subscriptionId = sim, messageId = rowId, options = SendOptions(deliveryReport = reports),
        )
        if (result.isFailure) {
            Log.w(TAG, "SmsManager send failed", result.exceptionOrNull())
            if (rowId == null) restoreUnsent(address, body)
        } else {
            runCatching { onMessageQueued() }
            // A reply clears the sender's automatic spam state (never a block or
            // the user's own Report spam), and is remembered past a deletion.
            runCatching { spamRepository?.recordReply(address) }
        }
        // No manual reload: the provider observer re-emits and reconciles.
    }

    /**
     * No row could be written (this app is not the default SMS app), so a failed
     * send has nowhere to show as failed. Take the optimistic row back and give
     * the text back to the compose box instead of leaving a phantom "sent".
     */
    private fun restoreUnsent(address: String, body: String) {
        optimistic = optimistic.filterNot { it.body == body && it.address == address }
        val current = _uiState.value
        _uiState.value = current.copy(
            messages = merged(),
            draft = if (current.draft.isEmpty()) body else current.draft,
        )
    }

    /** Sends a FAILED message again, keeping its row and place in the thread. */
    fun onRetry(messageId: Long) {
        val dataSource = this.dataSource ?: return
        val failed = _uiState.value.messages.firstOrNull { it.id.value == messageId && it.type == MessageType.FAILED } ?: return
        val sim = _uiState.value.selectedSimId
        viewModelScope.launch {
            dataSource.updateMessageType(messageId, MessageType.OUTBOX)
            deliver(failed.address, failed.body, sim, existingId = messageId)
        }
    }

    private fun fakeMessages(): List<Message> = listOf(
        Message(MessageId(1), ThreadId(1), "Alice", "Hey! Are we still on for lunch later? 🥗", System.currentTimeMillis() - 1000*60*20, MessageType.INBOX, true),
        Message(MessageId(2), ThreadId(1), "me", "Absolutely! I was thinking that new Thai place downtown?", System.currentTimeMillis() - 1000*60*15, MessageType.SENT, true),
        Message(MessageId(3), ThreadId(1), "Alice", "Perfect! I love Thai food.", System.currentTimeMillis() - 1000*60*10, MessageType.INBOX, true),
        Message(MessageId(4), ThreadId(1), "Alice", "Meet you there at 12:30?", System.currentTimeMillis() - 1000*60*5, MessageType.INBOX, true),
    )
}

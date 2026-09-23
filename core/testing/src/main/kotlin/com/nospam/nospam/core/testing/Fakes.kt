// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.testing

import com.nospam.nospam.core.common.PermissionChecker
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.*
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakePermissionChecker(
    private val granted: Set<String> = emptySet()
) : PermissionChecker {
    override fun hasPermission(permission: String): Boolean = permission in granted
    fun grant(permission: String) = FakePermissionChecker(granted + permission)
    fun revoke(permission: String) = FakePermissionChecker(granted - permission)
}

/**
 * Classifier stub. Defaults to ham so a test that does not care about
 * classification does not have to configure one.
 *
 * [verdictFor] decides the verdict per message; [classifyText] routes through
 * the same lambda with an empty sender, so both entry points stay in agreement.
 */
class FakeSpamClassifier(
    private val verdictFor: (RawMessage) -> SpamVerdict = { SpamVerdict(SpamLabel.HAM, -1.0) },
    /** Invoked on every `classify` call before the verdict is returned -- a seam
     *  for tests that need to observe or act mid-classification (e.g. simulate
     *  a concurrent write, or `yield`/cancel to make a scan's progress ticks
     *  or cancellation deterministic). No-op by default. */
    private val onClassify: suspend (RawMessage) -> Unit = {},
) : SpamClassifier {
    var lastMessage: RawMessage? = null
        private set
    var callCount: Int = 0
        private set

    override suspend fun classify(message: RawMessage): SpamVerdict {
        lastMessage = message
        callCount++
        onClassify(message)
        return verdictFor(message)
    }

    override suspend fun classifyText(text: String): SpamVerdict =
        classify(RawMessage(sender = "", body = text, timestamp = 0L))

    companion object {
        fun alwaysSpam(score: Double = 1.0) = FakeSpamClassifier(verdictFor = { SpamVerdict(SpamLabel.SPAM, score) })
        fun alwaysHam(score: Double = -1.0) = FakeSpamClassifier(verdictFor = { SpamVerdict(SpamLabel.HAM, score) })
        /** Spam when the body matches [predicate], ham otherwise. */
        fun spamWhen(predicate: (String) -> Boolean) = FakeSpamClassifier(verdictFor = {
            if (predicate(it.body)) SpamVerdict(SpamLabel.SPAM, 1.0) else SpamVerdict(SpamLabel.HAM, -1.0)
        })
    }
}

/**
 * In-memory stand-in for the system SMS provider.
 *
 * Reads come from mutable state a test can seed; writes are recorded so a test
 * can assert on them. Nothing here talks to a `ContentResolver`, so it runs on
 * the JVM. Behaviour is deliberately simple: this fake records what was asked
 * of it, it does not reimplement provider semantics.
 */
class FakeTelephonyDataSource(
    initialConversations: List<Conversation> = emptyList(),
) : TelephonyDataSource {

    private val conversationsFlow = MutableStateFlow(initialConversations)
    private val messagesByThread = mutableMapOf<Long, MutableStateFlow<List<Message>>>()

    /** Every `insertInboxMessage` call, in order: address, body, read flag. */
    val insertedInbox = mutableListOf<Triple<String, String, Boolean>>()
    /** Every `insertSentMessage` call, in order: address, body. */
    val insertedSent = mutableListOf<Pair<String, String>>()
    /** Thread ids passed to `deleteConversation`, in order. */
    val deletedThreadIds = mutableListOf<Long>()
    /** Message ids passed to `deleteMessage`, in order. */
    val deletedMessageIds = mutableListOf<Long>()
    /** Thread ids passed to `markAsRead`, in order (may contain duplicates). */
    val markedReadThreadIds = mutableListOf<Long>()
    /** Every `sendMessage` call, in order: address, body, subscriptionId. */
    val sentMessages = mutableListOf<Triple<String, String, Int?>>()
    /** The `messageId` passed to each `sendMessage`, in order; null when there was no OUTBOX row. */
    val sentMessageIds = mutableListOf<Long?>()
    /** Every `insertOutboxMessage` call, in order: address, body. */
    val insertedOutbox = mutableListOf<Pair<String, String>>()
    /** Every `updateMessageType` call, in order: message id, new type. */
    val updatedMessageTypes = mutableListOf<Pair<Long, MessageType>>()
    /** False models an app that is not the default SMS app: provider writes return null. */
    var writable = true
    /** Messages returned by `getAllMessages`. */
    val allMessages = mutableListOf<Message>()
    /** Addresses reported by `getOutboundSenderAddresses`. */
    val outboundAddresses = mutableSetOf<String>()
    /** Addresses reported as blocked by the system blocklist. */
    val systemBlocked = mutableSetOf<String>()
    /** Contact rows returned by `lookupContact`, keyed by address. */
    val contacts = mutableMapOf<String, Participant>()
    /** Every `updateMessageRead` call, in order: messageId, read flag. */
    val updatedMessageReads = mutableListOf<Pair<Long, Boolean>>()
    /** Contacts returned by `getContacts`, ignoring its limit/query arguments. */
    val contactEntries = mutableListOf<ContactEntry>()

    var nextThreadId: Long = 42L
    var sendResult: Result<Unit> = Result.success(Unit)
    var subscriptions: List<TelephonyDataSource.SimInfo> = emptyList()

    /**
     * When set, [getActiveSubscriptions] suspends until it completes: a seam for
     * testing screens opened before the SIM list has loaded.
     */
    var subscriptionsGate: CompletableDeferred<Unit>? = null
    /** When true, `insertInboxMessage` returns null instead of an incrementing id
     *  -- simulates a failed provider write (e.g. Result.messageId == null). */
    var failInsertInbox: Boolean = false
    /** When set, `getAllMessages` throws this instead of returning [allMessages]
     *  -- simulates a permission failure during a history scan. */
    var getAllMessagesError: Throwable? = null

    /** Replaces the conversation list and re-emits to `observeConversations`. */
    fun emitConversations(conversations: List<Conversation>) {
        conversationsFlow.value = conversations
    }

    /** Replaces one thread's messages and re-emits to `observeMessages`. */
    fun emitMessages(threadId: ThreadId, messages: List<Message>) {
        flowFor(threadId.value).value = messages
    }

    private fun flowFor(id: Long) = messagesByThread.getOrPut(id) { MutableStateFlow(emptyList()) }

    override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow

    /**
     * Mirrors RealTelephonyDataSource: observeMessages always serves just the
     * newest page (it's backed by `getMessages(threadId)` with no `before`),
     * never the full seeded history. Use [emitMessages] to seed/update the
     * full store and [getMessages] with `before` for older pages.
     */
    override fun observeMessages(threadId: ThreadId): Flow<List<Message>> =
        flowFor(threadId.value).map {
            it.sortedWith(compareBy({ m -> m.date }, { m -> m.id.value })).takeLast(TelephonyDataSource.MESSAGES_PAGE_SIZE)
        }

    override suspend fun getConversations(): List<Conversation> = conversationsFlow.value

    override suspend fun getMessages(threadId: ThreadId, limit: Int, before: Message?): List<Message> {
        // Same (date, id) key as the real query, so a fixture whose ids disagree
        // with its dates pages the way the provider does.
        val all = flowFor(threadId.value).value.sortedWith(compareBy({ it.date }, { it.id.value }))
        val older = if (before == null) all else all.filter {
            it.date < before.date || (it.date == before.date && it.id.value < before.id.value)
        }
        return older.takeLast(limit)
    }

    override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?, messageId: Long?): Result<Unit> {
        sentMessages.add(Triple(address, body, subscriptionId))
        sentMessageIds.add(messageId)
        return sendResult
    }

    override suspend fun insertOutboxMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
        insertedOutbox.add(address to body)
        return if (writable) 1000L + insertedOutbox.size else null
    }

    override suspend fun updateMessageType(messageId: Long, type: MessageType) {
        updatedMessageTypes.add(messageId to type)
    }

    override suspend fun markAsRead(threadId: ThreadId) {
        markedReadThreadIds.add(threadId.value)
    }

    override suspend fun markAsUnread(threadId: ThreadId) {}

    /** One entry per `setThreadsRead` call: the thread ids and the read flag. */
    val setThreadsReadCalls = mutableListOf<Pair<List<Long>, Boolean>>()

    override suspend fun setThreadsRead(threadIds: Collection<ThreadId>, read: Boolean) {
        setThreadsReadCalls += threadIds.map { it.value } to read
        if (read) markedReadThreadIds.addAll(threadIds.map { it.value })
    }

    override suspend fun deleteConversation(threadId: ThreadId) {
        deletedThreadIds.add(threadId.value)
    }

    /** One entry per `deleteConversations` call. */
    val deleteConversationsCalls = mutableListOf<List<Long>>()

    override suspend fun deleteConversations(threadIds: Collection<ThreadId>) {
        deleteConversationsCalls += threadIds.map { it.value }
        deletedThreadIds.addAll(threadIds.map { it.value })
    }

    override suspend fun deleteMessage(messageId: Long) {
        deletedMessageIds.add(messageId)
        messagesByThread.values.forEach { flow -> flow.value = flow.value.filterNot { it.id.value == messageId } }
    }

    override suspend fun insertInboxMessage(
        address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?
    ): Long? {
        insertedInbox.add(Triple(address, body, read))
        return if (failInsertInbox) null else insertedInbox.size.toLong()
    }

    override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
        insertedSent.add(address to body)
        return insertedSent.size.toLong()
    }

    override suspend fun getOrCreateThreadId(address: String): Long = nextThreadId

    override suspend fun updateMessageRead(messageId: Long, read: Boolean) {
        updatedMessageReads.add(messageId to read)
    }

    override suspend fun isSystemBlocked(address: String): Boolean = address in systemBlocked

    override suspend fun lookupContact(address: String): Participant? = contacts[address]

    /** Photo bytes by photo URI; a URI not in the map has no photo. */
    val contactPhotos = mutableMapOf<String, ByteArray>()
    /** Every URI [loadContactPhoto] was asked for, in order. */
    val loadedContactPhotos = mutableListOf<String>()

    /** When set, [loadContactPhoto] throws it: a stand-in for a provider failure. */
    var contactPhotoError: Exception? = null

    override suspend fun loadContactPhoto(photoUri: String): ByteArray? {
        loadedContactPhotos += photoUri
        contactPhotoError?.let { throw it }
        return contactPhotos[photoUri]
    }

    override suspend fun hasOutboundMessages(threadId: ThreadId): Boolean = false

    override suspend fun getOutboundSenderAddresses(): Set<String> = outboundAddresses

    override suspend fun getActiveSubscriptions(): List<TelephonyDataSource.SimInfo> {
        subscriptionsGate?.await()
        return subscriptions
    }

    override suspend fun searchBodyMatch(query: String): Set<Long> = emptySet()

    override suspend fun getContacts(limit: Int, query: String?): List<ContactEntry> = contactEntries

    override suspend fun getAllMessages(): List<Message> {
        getAllMessagesError?.let { throw it }
        return allMessages
    }
}

object TestData {
    val sampleConversation = Conversation(
        threadId = ThreadId(1),
        participants = listOf(Participant(address = "+989121234567", displayName = "Ali")),
        snippet = "سلام چطوری؟",
        date = System.currentTimeMillis(),
        messageCount = 3,
        read = false,
        isStarred = false,
        isPinned = true,
    )
    val sampleRawHam = RawMessage(sender = "+989121234567", body = "سلام، فردا میبینمت", timestamp = System.currentTimeMillis())
    val sampleRawSpam = RawMessage(sender = "1000", body = "You won! Click URLTOKEN to claim prize", timestamp = System.currentTimeMillis())
}

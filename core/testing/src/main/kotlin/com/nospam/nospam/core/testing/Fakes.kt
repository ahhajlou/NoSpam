package com.nospam.nospam.core.testing

import com.nospam.nospam.core.common.PermissionChecker
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.*
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
    private val verdictFor: (RawMessage) -> SpamVerdict = { SpamVerdict(SpamLabel.HAM, -1.0) }
) : SpamClassifier {
    var lastMessage: RawMessage? = null
        private set
    var callCount: Int = 0
        private set

    override suspend fun classify(message: RawMessage): SpamVerdict {
        lastMessage = message
        callCount++
        return verdictFor(message)
    }

    override suspend fun classifyText(text: String): SpamVerdict =
        classify(RawMessage(sender = "", body = text, timestamp = 0L))

    companion object {
        fun alwaysSpam(score: Double = 1.0) = FakeSpamClassifier { SpamVerdict(SpamLabel.SPAM, score) }
        fun alwaysHam(score: Double = -1.0) = FakeSpamClassifier { SpamVerdict(SpamLabel.HAM, score) }
        /** Spam when the body matches [predicate], ham otherwise. */
        fun spamWhen(predicate: (String) -> Boolean) = FakeSpamClassifier {
            if (predicate(it.body)) SpamVerdict(SpamLabel.SPAM, 1.0) else SpamVerdict(SpamLabel.HAM, -1.0)
        }
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
    /** Messages returned by `getAllMessages`. */
    val allMessages = mutableListOf<Message>()
    /** Addresses reported by `getOutboundSenderAddresses`. */
    val outboundAddresses = mutableSetOf<String>()
    /** Addresses reported as blocked by the system blocklist. */
    val systemBlocked = mutableSetOf<String>()
    /** Contact rows returned by `lookupContact`, keyed by address. */
    val contacts = mutableMapOf<String, Participant>()

    var nextThreadId: Long = 42L
    var sendResult: Result<Unit> = Result.success(Unit)
    var subscriptions: List<TelephonyDataSource.SimInfo> = emptyList()

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

    override fun observeMessages(threadId: ThreadId): Flow<List<Message>> = flowFor(threadId.value)

    override suspend fun getConversations(): List<Conversation> = conversationsFlow.value

    override suspend fun getMessages(threadId: ThreadId, limit: Int, beforeId: Long?): List<Message> {
        val all = flowFor(threadId.value).value
        val older = if (beforeId == null) all else all.filter { it.id.value < beforeId }
        return older.takeLast(limit)
    }

    override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = sendResult

    override suspend fun markAsRead(threadId: ThreadId) {}

    override suspend fun markAsUnread(threadId: ThreadId) {}

    override suspend fun deleteConversation(threadId: ThreadId) {
        deletedThreadIds.add(threadId.value)
    }

    override suspend fun insertInboxMessage(
        address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?
    ): Long? {
        insertedInbox.add(Triple(address, body, read))
        return insertedInbox.size.toLong()
    }

    override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
        insertedSent.add(address to body)
        return insertedSent.size.toLong()
    }

    override suspend fun getOrCreateThreadId(address: String): Long = nextThreadId

    override suspend fun updateMessageRead(messageId: Long, read: Boolean) {}

    override suspend fun isSystemBlocked(address: String): Boolean = address in systemBlocked

    override suspend fun lookupContact(address: String): Participant? = contacts[address]

    override suspend fun hasOutboundMessages(threadId: ThreadId): Boolean = false

    override suspend fun getOutboundSenderAddresses(): Set<String> = outboundAddresses

    override suspend fun getActiveSubscriptions(): List<TelephonyDataSource.SimInfo> = subscriptions

    override suspend fun searchBodyMatch(query: String): Set<Long> = emptySet()

    override suspend fun getContacts(limit: Int, query: String?): List<ContactEntry> = emptyList()

    override suspend fun getAllMessages(): List<Message> = allMessages
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

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.ContactEntry
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpamBackfillUseCaseTest {

    private class FakeTelephony(
        var messages: List<Message> = emptyList(),
        var outbound: Set<String> = emptySet(),
        var contacts: List<ContactEntry> = emptyList(),
        var throwOnGetAll: Boolean = false,
    ) : TelephonyDataSource {
        override suspend fun getAllMessages(): List<Message> {
            if (throwOnGetAll) throw SecurityException("no permission")
            return messages
        }
        override suspend fun getOutboundSenderAddresses(): Set<String> = outbound
        override suspend fun getContacts(limit: Int, query: String?): List<ContactEntry> = contacts
        override fun observeMessages(threadId: ThreadId): Flow<List<Message>> = MutableStateFlow(emptyList())
        override fun observeConversations(): Flow<List<Conversation>> = MutableStateFlow(emptyList())
        override suspend fun getConversations(): List<Conversation> = emptyList()
        override suspend fun getMessages(threadId: ThreadId): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun markAsUnread(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?): Long? = null
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? = null
        override suspend fun searchBodyMatch(query: String): Set<Long> = emptySet()
        override suspend fun getActiveSubscriptions(): List<TelephonyDataSource.SimInfo> = emptyList()
        override suspend fun hasOutboundMessages(threadId: ThreadId): Boolean = false
        override suspend fun lookupContact(address: String): Participant? = null
        override suspend fun isSystemBlocked(address: String): Boolean = false
        override suspend fun updateMessageRead(messageId: Long, read: Boolean) {}
        override suspend fun getOrCreateThreadId(address: String): Long = 1L
    }

    private class FakeClassifier(
        private val verdictFor: (RawMessage) -> SpamVerdict,
        private val onClassify: suspend (RawMessage) -> Unit = {},
    ) : SpamClassifier {
        var callCount = 0
            private set
        override suspend fun classify(message: RawMessage): SpamVerdict {
            callCount++
            onClassify(message)
            return verdictFor(message)
        }
        override suspend fun classifyText(text: String) = SpamVerdict(SpamLabel.HAM, -1.0)
    }

    private fun classifierWhere(isSpam: (RawMessage) -> Boolean): FakeClassifier = FakeClassifier(
        verdictFor = { if (isSpam(it)) SpamVerdict(SpamLabel.SPAM, 2.0) else SpamVerdict(SpamLabel.HAM, -1.0) }
    )

    private fun inbox(id: Long, address: String, body: String, date: Long, threadId: Long = id): Message =
        Message(MessageId(id), ThreadId(threadId), address, body, date, MessageType.INBOX, read = false)

    private fun recentAgo(secondsAgo: Long): Long = System.currentTimeMillis() - secondsAgo * 1000L

    private fun useCase(
        db: NoSpamDatabase,
        telephony: FakeTelephony,
        classifier: SpamClassifier,
        protectionEnabled: Boolean = true,
        writer: SpamStateWriter = SpamStateWriter(db.senderStateDao),
        scope: TestScope,
    ) = SpamBackfillUseCase(
        telephony = telephony,
        classifier = classifier,
        db = db,
        spamStateWriter = writer,
        isSpamProtectionEnabled = { protectionEnabled },
        externalScope = scope,
    )

    @Test fun `new sender single spam grades to SPAM and stores verdict`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephony(messages = listOf(inbox(1, "+98912", "win prize now", recentAgo(60))))
        val classifier = classifierWhere { true }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Done, backfill.status.value)
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertNotNull(db.messageVerdictDao.getByMessageId(1))
        assertEquals(1, classifier.callCount)
    }

    @Test fun `mixed sender graduates to SPAM after 3+ spams at 80 percent`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val msgs = listOf(
            inbox(1, "+989111111111", "hello", recentAgo(500)),
            inbox(2, "+989111111111", "spam1", recentAgo(400)),
            inbox(3, "+989111111111", "spam2", recentAgo(300)),
            inbox(4, "+989111111111", "spam3", recentAgo(200)),
            inbox(5, "+989111111111", "spam4", recentAgo(100)),
        )
        val classifier = classifierWhere { it.body != "hello" }
        val backfill = useCase(db, FakeTelephony(messages = msgs), classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        // ham + 4 spam = 4/5 = 80% and >= 3 spams -> graduate
        val state = db.senderStateDao.getByAddress("+989111111111")!!
        assertEquals(ThreadSpamState.SPAM, state.state)
        assertEquals(4, state.spamCount)
        assertEquals(1, state.hamCount)
    }

    @Test fun `contact or outbound sender never promotes to SPAM`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val address = "+989222222222"
        // 1 ham + 6 spams (brutal) — still only MIXED because the user has replied
        val msgs = (1L..7L).map { inbox(it, address, if (it == 1L) "hi" else "spam$it", recentAgo(700 - it * 60)) }
        val classifier = classifierWhere { it.body != "hi" }
        val backfill = useCase(
            db, FakeTelephony(messages = msgs, outbound = setOf(address)), classifier, scope = this,
        )

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(ThreadSpamState.MIXED, db.senderStateDao.getByAddress(address)!!.state)
    }

    @Test fun `contact sender via contacts list never promotes to SPAM`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val address = "+989333333333"
        val msgs = (1L..7L).map { inbox(it, address, "spam$it", recentAgo(700 - it * 60)) }
        val classifier = classifierWhere { true }
        val backfill = useCase(
            db,
            FakeTelephony(messages = msgs, contacts = listOf(ContactEntry(1L, "Ali", address, address))),
            classifier,
            scope = this,
        )

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(ThreadSpamState.MIXED, db.senderStateDao.getByAddress(address)!!.state)
    }

    @Test fun `user override is never touched`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.TRUSTED, isUserOverride = true))
        val telephony = FakeTelephony(messages = listOf(inbox(1, "+98912", "win prize now", recentAgo(60))))
        val classifier = classifierWhere { true }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Done, backfill.status.value)
        val state = db.senderStateDao.getByAddress("+98912")!!
        assertEquals(ThreadSpamState.TRUSTED, state.state)
        assertTrue(state.isUserOverride)
        assertNull(db.messageVerdictDao.getByMessageId(1))
        assertEquals(0, classifier.callCount)
    }

    @Test fun `partial completion resumes without double counting`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephony(
            messages = listOf(inbox(1, "+98912", "spam a", recentAgo(200)), inbox(2, "+98912", "spam b", recentAgo(100))),
        )
        val classifier = classifierWhere { true }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertEquals(2, classifier.callCount)

        // A third message arrives; scan re-runs.
        telephony.messages = telephony.messages + inbox(3, "+98912", "spam c", recentAgo(50))
        backfill.ensureStarted()
        advanceUntilIdle()

        // Only the new message was classified; sticky SPAM keeps its single count.
        assertEquals(3, classifier.callCount)
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertEquals(1, db.senderStateDao.getByAddress("+98912")!!.spamCount)
        assertNotNull(db.messageVerdictDao.getByMessageId(3))
    }

    @Test fun `concurrently ingressed message count is merged not clobbered`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val writer = SpamStateWriter(db.senderStateDao)
        val telephony = FakeTelephony(messages = listOf(inbox(1, "+98912", "old spam", recentAgo(200))))
        // While the backfill classifies the old message, a NEW message from the same
        // sender ingresses through the shared writer (simulated inside onClassify).
        val classifier = FakeClassifier({ SpamVerdict(SpamLabel.SPAM, 2.0) }) {
            if (db.senderStateDao.getByAddress("+98912") == null) {
                writer.upsertIfNotOverridden("+98912") { SenderStateEntity("+98912", ThreadSpamState.SPAM, spamCount = 1) }
            }
        }
        val backfill = useCase(db, telephony, classifier, writer = writer, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        // Backfill aggregates 1 for the old message; the in-flight ingress adds 1 more.
        assertEquals(2, db.senderStateDao.getByAddress("+98912")!!.spamCount)
    }

    @Test fun `cancel stops between senders and leaves them unclassified`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephony(
            messages = listOf(
                inbox(1, "+98912", "spam a", recentAgo(300)),
                inbox(2, "+98912", "spam b", recentAgo(200)),
                inbox(3, "+98999", "spam c", recentAgo(150)),
                inbox(4, "+98999", "spam d", recentAgo(100)),
            ),
        )
        var holder: SpamBackfillUseCase? = null
        val classifier = FakeClassifier({ SpamVerdict(SpamLabel.SPAM, 2.0) }) { holder?.cancel() }
        val backfill = useCase(db, telephony, classifier, scope = this)
        holder = backfill

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Cancelled, backfill.status.value)
        // Neither sender was persisted (fail-open).
        assertNull(db.senderStateDao.getByAddress("+98912"))
        assertNull(db.senderStateDao.getByAddress("+98999"))
    }

    @Test fun `security exception aborts as Failed`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephony(throwOnGetAll = true)
        val backfill = useCase(db, telephony, classifierWhere { false }, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Failed, backfill.status.value)
    }

    @Test fun `spam protection off forces CLEAN but still records verdict evidence`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephony(messages = listOf(inbox(1, "+98912", "win prize", recentAgo(60))))
        val backfill = useCase(db, telephony, classifierWhere { true }, protectionEnabled = false, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(ThreadSpamState.CLEAN, db.senderStateDao.getByAddress("+98912")!!.state)
        val verdict = db.messageVerdictDao.getByMessageId(1)
        assertNotNull(verdict)
        assertTrue(verdict!!.isSpam)
    }

    @Test fun `old messages vote on sender state but store no verdict row`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val oldDate = System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L
        val msgs = listOf(
            inbox(1, "+98912", "ancient spam", oldDate),
            inbox(2, "+98912", "recent ham", System.currentTimeMillis()),
        )
        val classifier = classifierWhere { it.body == "ancient spam" }
        val backfill = useCase(db, FakeTelephony(messages = msgs), classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        // Old spam voted -> SPAM (sticky, the recent ham can't rescue).
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertNull(db.messageVerdictDao.getByMessageId(1))
        assertNotNull(db.messageVerdictDao.getByMessageId(2))
    }
}
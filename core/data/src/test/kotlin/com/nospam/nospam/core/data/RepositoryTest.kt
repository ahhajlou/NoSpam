package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    // Mirrors PhoneNumberNormalizer behavior for tests (no Android Context).
    // "0"-prefixed local form → E.164 +98…, everything else trim/uppercase.
    // Deterministic in CI.
    private val e164Normalizer: (String) -> String = { raw ->
        val t = raw.trim()
        if (t.startsWith("0") && t.length >= 10) "+98" + t.drop(1) else t.uppercase()
    }
    class FakeTelephony(private val convs: List<Conversation> = emptyList()) : TelephonyDataSource {
        private val flow = MutableStateFlow(convs)
        fun emit(convs: List<Conversation>) { flow.value = convs }
        val inserted = mutableListOf<Triple<String, String, Boolean>>()
        var nextThreadId: Long = 42L
        override fun observeMessages(threadId: ThreadId): Flow<List<Message>> = MutableStateFlow(emptyList())
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override suspend fun getConversations(): List<Conversation> = flow.value
        override suspend fun getMessages(threadId: ThreadId): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun markAsUnread(threadId: ThreadId) {}
        val deletedIds = mutableListOf<Long>()
        override suspend fun deleteConversation(threadId: ThreadId) {
            deletedIds.add(threadId.value)
        }
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?): Long? {
            inserted.add(Triple(address, body, read))
            return 1L
        }
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? = 2L
        override suspend fun getContacts(limit: Int, query: String?): List<com.nospam.nospam.core.model.ContactEntry> = emptyList()
        override suspend fun searchBodyMatch(query: String): Set<Long> = emptySet()
        override suspend fun getActiveSubscriptions(): List<com.nospam.nospam.core.telephony.TelephonyDataSource.SimInfo> = emptyList()
        override suspend fun hasOutboundMessages(threadId: com.nospam.nospam.core.model.ThreadId): Boolean = false
        override suspend fun lookupContact(address: String): com.nospam.nospam.core.model.Participant? = null
        override suspend fun isSystemBlocked(address: String): Boolean = false
        override suspend fun updateMessageRead(messageId: Long, read: Boolean) {}
        override suspend fun getOrCreateThreadId(address: String): Long = nextThreadId
        override suspend fun getAllMessages(): List<Message> = emptyList()
    }

    class FakeClassifier(private val isSpam: Boolean = false) : SpamClassifier {
        override suspend fun classify(message: RawMessage) = SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 1.0 else -1.0)
        override suspend fun classifyText(text: String) = SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 1.0 else -1.0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `legacy spam_verdict without sender state does not move conversation`() = runTest {
        val conv = Conversation(ThreadId(1), listOf(Participant("+98912")), "hello", System.currentTimeMillis(), 1, false)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope)
        // Only a legacy threadId-keyed verdict exists, no SenderState.
        db.spamVerdictDao.upsert(
            com.nospam.nospam.core.database.entity.SpamVerdictEntity(
                threadId = 1, isSpam = true, score = 1.0
            )
        )
        val inbox = repo.observeConversations().take(1).toList().first()
        val spam = repo.observeSpam().take(1).toList().first()
        // Legacy table must NOT drive list membership anymore (single source = sender_state).
        assertEquals(1, inbox.size)
        assertTrue(spam.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `observeConversations re-emits on provider change`() = runTest {
        val first = Conversation(ThreadId(1), listOf(Participant("+98912")), "one", 1L, 1, true)
        val second = Conversation(ThreadId(2), listOf(Participant("+98913")), "two", 2L, 1, true)
        val tele = FakeTelephony(listOf(first))
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, NoSpamDatabase.inMemory(), testScope)
        val emissions = mutableListOf<List<Conversation>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeConversations().collect { emissions.add(it) }
        }
        testScheduler.advanceUntilIdle()
        tele.emit(listOf(first, second))
        testScheduler.advanceUntilIdle()
        job.cancel()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions[0].size)
        assertEquals(2, emissions[1].size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `archive hides from inbox and shows in archived`() = runTest {
        val conv = Conversation(ThreadId(1), listOf(Participant("+98912")), "hello", 1L, 1, true)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope)
        assertEquals(1, repo.observeConversations().take(1).toList().first().size)

        repo.archive(ThreadId(1))
        val inbox = repo.observeConversations().take(1).toList().first()
        assertTrue(inbox.isEmpty())
        val archived = repo.observeArchived().take(1).toList().first()
        assertEquals(1, archived.size)
        assertTrue(archived.first().isArchived)

        repo.unarchive(ThreadId(1))
        assertEquals(1, repo.observeConversations().take(1).toList().first().size)
    }

    @Test fun `deleteConversation drops provider and app rows`() = runTest {
        val conv = Conversation(ThreadId(1), listOf(Participant("+98912")), "hello", 1L, 1, true)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(
            com.nospam.nospam.core.database.entity.SpamVerdictEntity(
                threadId = 1, isSpam = true, score = 1.0
            )
        )
        db.archivedDao.archive(1)
        val repo = ConversationsRepository(tele, db)
        repo.deleteConversation(ThreadId(1))
        assertEquals(listOf(1L), tele.deletedIds)
        assertNull(db.spamVerdictDao.getByThread(1))
        assertFalse(db.archivedDao.isArchived(1))
    }

    @Test fun `BlocklistRepository blocks`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+98912")
        assertTrue(repo.isBlocked("+98912"))
        repo.unblock("+98912")
        assertFalse(repo.isBlocked("+98912"))
    }

    @Test fun `SpamRepository markNotSpam overrides`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = SpamRepository(db, FakeClassifier(true))
        repo.classifyAndStore(ThreadId(5), RawMessage("x", "spam", 0L))
        assertTrue(repo.getVerdict(ThreadId(5))!!.isSpam)
        repo.markNotSpam(ThreadId(5), "+98912")
        val v = repo.getVerdict(ThreadId(5))!!
        assertFalse(v.isSpam)
        assertTrue(v.isUserOverride)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `spam membership single source by sender state`() = runTest {
        val states = listOf(
            Triple(1L, "09111111111", ThreadSpamState.CLEAN),
            Triple(2L, "09222222222", ThreadSpamState.MIXED),
            Triple(3L, "09333333333", ThreadSpamState.TRUSTED),
            Triple(4L, "09444444444", ThreadSpamState.SPAM),
            Triple(5L, "09555555555", ThreadSpamState.BLOCKED),
        )
        val convs = states.map { (id, addr, _) ->
            Conversation(ThreadId(id), listOf(Participant(addr)), "x", 1L, 1, true)
        }
        val tele = FakeTelephony(convs)
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope, e164Normalizer)
        states.forEach { (_, addr, state) ->
            db.senderStateDao.upsert(
                com.nospam.nospam.core.database.entity.SenderStateEntity(
                    normalizedAddress = e164Normalizer(addr),
                    state = state,
                    spamCount = 1,
                    hamCount = 1,
                )
            )
        }
        val inbox = repo.observeConversations().take(1).toList().first()
        val spam = repo.observeSpam().take(1).toList().first()
        assertEquals(setOf(1L, 2L, 3L), inbox.map { it.threadId.value }.toSet())
        assertEquals(setOf(4L, 5L), spam.map { it.threadId.value }.toSet())
        // The split bug: same conversation must never appear in both lists.
        assertTrue(inbox.map { it.threadId.value }.toSet().intersect(spam.map { it.threadId.value }.toSet()).isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `raw address still matched against E164 sender state`() = runTest {
        // Conversation participant is the raw local form (0912…), SenderState was
        // stored under the E.164 form (+98912…) by ingress. Lookup must unify them —
        // this exact mismatch caused the inbox/spam split.
        val conv = Conversation(ThreadId(7), listOf(Participant("09123456789")), "hello", 1L, 1, true)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope, e164Normalizer)
        db.senderStateDao.upsert(
            com.nospam.nospam.core.database.entity.SenderStateEntity(
                normalizedAddress = "+989123456789",
                state = ThreadSpamState.SPAM,
                spamCount = 1,
                isUserOverride = true,
            )
        )
        val inbox = repo.observeConversations().take(1).toList().first()
        val spam = repo.observeSpam().take(1).toList().first()
        assertTrue(inbox.isEmpty())
        assertEquals(listOf(7L), spam.map { it.threadId.value })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `markSpam then markNotSpam moves conversation out of spam`() = runTest {
        val addr = "+989123456789" // already E.164 — same key in repo + SpamRepository (no context)
        val conv = Conversation(ThreadId(9), listOf(Participant(addr)), "hello", 1L, 1, true)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope, e164Normalizer)
        val spamRepo = SpamRepository(db, FakeClassifier(true))
        spamRepo.markSpam(ThreadId(9), addr)
        assertEquals(listOf(9L), repo.observeSpam().take(1).toList().first().map { it.threadId.value })
        assertEquals(0, repo.observeConversations().take(1).toList().first().size)

        spamRepo.markNotSpam(ThreadId(9), addr)
        assertTrue(repo.observeSpam().take(1).toList().first().isEmpty())
        assertEquals(listOf(9L), repo.observeConversations().take(1).toList().first().map { it.threadId.value })
    }
}

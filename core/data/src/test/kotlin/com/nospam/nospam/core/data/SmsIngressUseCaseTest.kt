package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SmsIngressUseCaseTest {
    private class FakeTelephony(
        var threadId: Long = 7L,
        var insertResult: Long? = 99L,
        var contactName: String? = null,
    ) : TelephonyDataSource {
        val inserted = mutableListOf<Triple<String, String, Boolean>>()
        val updatedReads = mutableListOf<Pair<Long, Boolean>>()
        private val flow = MutableStateFlow<List<Conversation>>(emptyList())
        override fun observeMessages(threadId: ThreadId): Flow<List<Message>> = MutableStateFlow(emptyList())
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override suspend fun getConversations(): List<Conversation> = emptyList()
        override suspend fun getMessages(threadId: ThreadId, limit: Int, beforeId: Long?): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> =
            Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun markAsUnread(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?): Long? {
            inserted.add(Triple(address, body, read))
            return insertResult
        }
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? = 2L
        override suspend fun getContacts(limit: Int, query: String?): List<com.nospam.nospam.core.model.ContactEntry> = emptyList()
        override suspend fun searchBodyMatch(query: String): Set<Long> = emptySet()
        override suspend fun getActiveSubscriptions(): List<com.nospam.nospam.core.telephony.TelephonyDataSource.SimInfo> = emptyList()
        override suspend fun hasOutboundMessages(threadId: com.nospam.nospam.core.model.ThreadId): Boolean = false
        override suspend fun getOutboundSenderAddresses(): Set<String> = emptySet()
        override suspend fun lookupContact(address: String): com.nospam.nospam.core.model.Participant? =
            contactName?.let { com.nospam.nospam.core.model.Participant(address = address, displayName = it) }
        override suspend fun isSystemBlocked(address: String): Boolean = false
        override suspend fun updateMessageRead(messageId: Long, read: Boolean) { updatedReads.add(messageId to read) }
        override suspend fun getOrCreateThreadId(address: String): Long = threadId
        override suspend fun getAllMessages(): List<Message> = emptyList()
    }

    private class FakeClassifier(private val isSpam: Boolean) : SpamClassifier {
        override suspend fun classify(message: RawMessage) =
            SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 2.0 else -2.0)
        override suspend fun classifyText(text: String) =
            SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 2.0 else -2.0)
    }

    /**
     * Holds the read → write window open so two concurrent ingress calls
     * interleave deterministically instead of depending on thread timing.
     */
    private class SlowReadSenderStateDao(
        private val delegate: com.nospam.nospam.core.database.dao.SenderStateDao,
    ) : com.nospam.nospam.core.database.dao.SenderStateDao by delegate {
        override suspend fun getByAddress(
            normalizedAddress: String,
        ): com.nospam.nospam.core.database.entity.SenderStateEntity? {
            val value = delegate.getByAddress(normalizedAddress)
            kotlinx.coroutines.yield()
            return value
        }
    }

    @Test fun `two messages from one sender arriving together both count`() = runTest {
        val db = NoSpamDatabase(
            senderStateDao = SlowReadSenderStateDao(
                com.nospam.nospam.core.database.dao.InMemorySenderStateDao()
            )
        )
        // A known contact, so the policy lands on MIXED and keeps counting.
        // A stranger would go straight to SPAM, which is sticky by design and
        // stops incrementing, hiding the race.
        val useCase = SmsIngressUseCase(
            FakeTelephony(contactName = "Bank Mellat"),
            FakeClassifier(isSpam = true),
            db,
        )

        // Each SMS_DELIVER broadcast runs its own coroutine on Dispatchers.IO,
        // so two messages from one short code can be in flight at once.
        listOf(
            launch { useCase.handle(RawMessage("+98912", "win prize now", 1L)) },
            launch { useCase.handle(RawMessage("+98912", "claim your prize", 2L)) },
        ).joinAll()

        val state = db.senderStateDao.getByAddress("+98912")!!
        assertEquals(2, state.spamCount)
    }

    @Test fun `spam is inserted as read and verdict stored`() = runTest {
        val telephony = FakeTelephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony, FakeClassifier(isSpam = true), db)

        val result = useCase.handle(RawMessage("+98912", "win prize now", 12345L))

        assertTrue(result.isSpam)
        assertEquals(ThreadId(7L), result.threadId)
        assertEquals(99L, result.messageId)
        // Inserted unread first, then marked read for spam.
        assertEquals(listOf(Triple("+98912", "win prize now", false)), telephony.inserted)
        assertEquals(listOf(99L to true), telephony.updatedReads)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertTrue(verdict.isSpam)
        assertFalse(verdict.isUserOverride)
    }

    @Test fun `classifier failure still persists message`() = runTest {
        val telephony = FakeTelephony()
        val db = NoSpamDatabase.inMemory()
        val failingClassifier = object : SpamClassifier {
            override suspend fun classify(message: RawMessage): SpamVerdict = throw RuntimeException("model fail")
            override suspend fun classifyText(text: String): SpamVerdict = throw RuntimeException("model fail")
        }
        val useCase = SmsIngressUseCase(telephony, failingClassifier, db)
        val result = useCase.handle(RawMessage("+98912", "hello", 12345L))
        // Message persisted despite classifier failure
        assertEquals(1, telephony.inserted.size)
        assertEquals(99L, result.messageId)
        assertFalse(result.isSpam)
        assertNull(db.spamVerdictDao.getByThread(7L))
        assertTrue(telephony.updatedReads.isEmpty())
    }

    @Test fun `ham is inserted as unread`() = runTest {
        val telephony = FakeTelephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony, FakeClassifier(isSpam = false), db)

        val result = useCase.handle(RawMessage("+98912", "see you tomorrow", 12345L))

        assertFalse(result.isSpam)
        assertEquals(listOf(Triple("+98912", "see you tomorrow", false)), telephony.inserted)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertFalse(verdict.isSpam)
    }

    @Test fun `insert failure still returns verdict`() = runTest {
        val telephony = FakeTelephony(insertResult = null)
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony, FakeClassifier(isSpam = false), db)

        val result = useCase.handle(RawMessage("+98912", "hi", 1L))

        assertNull(result.messageId)
        assertNotNull(db.spamVerdictDao.getByThread(7L))
    }

    @Test fun `pruneOldSpam keeps user overrides`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = SpamRepository(db, FakeClassifier(isSpam = true))
        val old = System.currentTimeMillis() - 31L * 24L * 60L * 60L * 1000L
        db.spamVerdictDao.upsert(
            SpamVerdictEntity(threadId = 1L, isSpam = true, score = 1.0, isUserOverride = false, updatedAt = old)
        )
        db.spamVerdictDao.upsert(
            SpamVerdictEntity(threadId = 2L, isSpam = true, score = 1.0, isUserOverride = true, updatedAt = old)
        )
        assertEquals(1, repo.pruneOldSpam())
        assertNull(db.spamVerdictDao.getByThread(1L))
        assertNotNull(db.spamVerdictDao.getByThread(2L))
    }

    @Test fun `retention prunes only auto ham rows`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val cutoff = System.currentTimeMillis() - SpamRepository.SPAM_RETENTION_DAYS * 24L * 60L * 60L * 1000L
        val old = cutoff - 1L
        // Auto spam older than the cutoff must survive forever (per-message marker).
        db.messageVerdictDao.insert(MessageVerdictEntity(1L, 1L, "+98912", isSpam = true, score = 1.0, createdAt = old))
        // Auto ham older than the cutoff is the only thing pruned.
        db.messageVerdictDao.insert(MessageVerdictEntity(2L, 1L, "+98912", isSpam = false, score = -1.0, createdAt = old))
        // User-labeled rows are never pruned.
        db.messageVerdictDao.insert(MessageVerdictEntity(3L, 1L, "+98912", isSpam = true, score = 1.0, createdAt = old, userLabel = false))

        db.messageVerdictDao.deleteAutoOlderThan(cutoff)

        assertNotNull(db.messageVerdictDao.getByMessageId(1L))
        assertNull(db.messageVerdictDao.getByMessageId(2L))
        assertNotNull(db.messageVerdictDao.getByMessageId(3L))
    }
}

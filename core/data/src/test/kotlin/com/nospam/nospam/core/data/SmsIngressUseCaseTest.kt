package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.dao.InMemorySenderStateDao
import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SmsIngressUseCaseTest {

    /**
     * Holds the read -> write window open so two concurrent ingress calls
     * interleave deterministically instead of depending on thread timing.
     */
    private class SlowReadSenderStateDao(
        private val delegate: SenderStateDao,
    ) : SenderStateDao by delegate {
        override suspend fun getByAddress(normalizedAddress: String): SenderStateEntity? {
            val value = delegate.getByAddress(normalizedAddress)
            yield()
            return value
        }
    }

    private fun telephony(threadId: Long = 7L, contactName: String? = null) = FakeTelephonyDataSource().apply {
        nextThreadId = threadId
        contactName?.let { contacts["+98912"] = Participant(address = "+98912", displayName = it) }
    }

    @Test fun `two messages from one sender arriving together both count`() = runTest {
        val db = NoSpamDatabase(senderStateDao = SlowReadSenderStateDao(InMemorySenderStateDao()))
        // A known contact, so the policy lands on MIXED and keeps counting.
        // A stranger would go straight to SPAM, which is sticky by design and
        // stops incrementing, hiding the race.
        val useCase = SmsIngressUseCase(
            telephony(contactName = "Bank Mellat"),
            FakeSpamClassifier.alwaysSpam(2.0),
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
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(fake, FakeSpamClassifier.alwaysSpam(2.0), db)

        val result = useCase.handle(RawMessage("+98912", "win prize now", 12345L))

        assertTrue(result.isSpam)
        assertEquals(ThreadId(7L), result.threadId)
        assertEquals(1L, result.messageId)
        // Inserted unread first, then marked read for spam.
        assertEquals(listOf(Triple("+98912", "win prize now", false)), fake.insertedInbox)
        assertEquals(listOf(1L to true), fake.updatedMessageReads)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertTrue(verdict.isSpam)
        assertFalse(verdict.isUserOverride)
    }

    @Test fun `classifier failure still persists message`() = runTest {
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        val failingClassifier = FakeSpamClassifier(verdictFor = { throw RuntimeException("model fail") })
        val useCase = SmsIngressUseCase(fake, failingClassifier, db)
        val result = useCase.handle(RawMessage("+98912", "hello", 12345L))
        // Message persisted despite classifier failure
        assertEquals(1, fake.insertedInbox.size)
        assertEquals(1L, result.messageId)
        assertFalse(result.isSpam)
        assertNull(db.spamVerdictDao.getByThread(7L))
        assertTrue(fake.updatedMessageReads.isEmpty())
    }

    @Test fun `ham is inserted as unread`() = runTest {
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(fake, FakeSpamClassifier.alwaysHam(-2.0), db)

        val result = useCase.handle(RawMessage("+98912", "see you tomorrow", 12345L))

        assertFalse(result.isSpam)
        assertEquals(listOf(Triple("+98912", "see you tomorrow", false)), fake.insertedInbox)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertFalse(verdict.isSpam)
    }

    @Test fun `insert failure still returns verdict`() = runTest {
        val fake = telephony().apply { failInsertInbox = true }
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(fake, FakeSpamClassifier.alwaysHam(), db)

        val result = useCase.handle(RawMessage("+98912", "hi", 1L))

        assertNull(result.messageId)
        assertNotNull(db.spamVerdictDao.getByThread(7L))
    }

    @Test fun `pruneOldSpam keeps user overrides`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = SpamRepository(db, FakeSpamClassifier.alwaysSpam())
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

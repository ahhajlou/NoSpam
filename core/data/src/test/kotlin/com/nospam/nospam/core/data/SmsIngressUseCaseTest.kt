// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.dao.BlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryBlocklistDao
import com.nospam.nospam.core.database.dao.InMemorySenderStateDao
import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.ml.DeferredSpamClassifier
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.NotificationDecision
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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

    /**
     * A model whose load blocks a thread until [release], as the real one does
     * (a 1.9 MB JSON model plus 14 MB of word lists): it cannot be canceled,
     * so only a wait that really ends at the budget gets past it.
     */
    private class BlockingLoad(loaded: SpamClassifier) {
        val release = CountDownLatch(1)
        val classifier = DeferredSpamClassifier(Dispatchers.IO) {
            release.await(5, TimeUnit.SECONDS)
            loaded
        }
    }

    /** Runs the test scheduler until [condition] holds, for work resumed from a real thread. */
    private suspend fun TestScope.awaitReal(condition: suspend () -> Boolean) {
        repeat(500) {
            runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not met within 5s")
    }

    private fun telephony(threadId: Long = 7L, contactName: String? = null) = FakeTelephonyDataSource().apply {
        nextThreadId = threadId
        contactName?.let { contacts["+98912"] = Participant(address = "+98912", displayName = it) }
    }

    @Test fun `two messages from one sender arriving together both count`() = runTest {
        val db = NoSpamDatabase(senderStateDao = SlowReadSenderStateDao(InMemorySenderStateDao()))
        // Every state keeps counting, so a lost increment shows in the count.
        val useCase = SmsIngressUseCase(
            telephony(),
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

    @Test fun `a new sender's first spam stays unread in the inbox as SUSPECTED`() = runTest {
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(fake, FakeSpamClassifier.alwaysSpam(2.0), db)

        val result = useCase.handle(RawMessage("+98912", "win prize now", 12345L))

        assertTrue(result.isSpam)
        assertEquals(ThreadId(7L), result.threadId)
        assertEquals(1L, result.messageId)
        assertEquals(ThreadSpamState.SUSPECTED, result.senderState)
        assertEquals(NotificationDecision.SILENT, result.notificationDecision)
        // Inserted unread, and left unread so it can still be found.
        assertEquals(listOf(Triple("+98912", "win prize now", false)), fake.insertedInbox)
        assertEquals(emptyList<Pair<Long, Boolean>>(), fake.updatedMessageReads)
        assertNotNull(db.messageVerdictDao.getByMessageId(1L))
    }

    @Test fun `spam routed to Spam is marked read and verdict stored`() = runTest {
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(fake, FakeSpamClassifier.alwaysSpam(2.0), db)

        useCase.handle(RawMessage("+98912", "win prize now", 12345L))
        val result = useCase.handle(RawMessage("+98912", "claim your prize", 12346L))

        assertEquals(ThreadSpamState.SPAM, result.senderState)
        assertEquals(NotificationDecision.NONE, result.notificationDecision)
        // Inserted unread first, then marked read once it goes to Spam.
        assertEquals(listOf(result.messageId!! to true), fake.updatedMessageReads)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertTrue(verdict.isSpam)
        assertFalse(verdict.isUserOverride)
    }

    @Test fun `a saved contact's message is not classified and notifies normally`() = runTest {
        val fake = telephony(contactName = "Bank Mellat")
        val db = NoSpamDatabase.inMemory()
        val classifier = FakeSpamClassifier.alwaysSpam(2.0)
        val useCase = SmsIngressUseCase(fake, classifier, db)

        val result = useCase.handle(RawMessage("+98912", "win prize now", 12345L))

        assertEquals(0, classifier.callCount)
        assertFalse(result.isSpam)
        assertEquals(NotificationDecision.NORMAL, result.notificationDecision)
        assertEquals(emptyList<Pair<Long, Boolean>>(), fake.updatedMessageReads)
        assertNull(db.senderStateDao.getByAddress("+98912"))
        assertNull(db.messageVerdictDao.getByMessageId(1L))
        // Returned for the notification, so it shows the name, not the number.
        assertEquals("Bank Mellat", result.contact?.displayName)
    }

    @Test fun `a stranger's message carries no contact`() = runTest {
        val result = SmsIngressUseCase(telephony(), FakeSpamClassifier.alwaysHam(), NoSpamDatabase.inMemory())
            .handle(RawMessage("+98912", "hello", 12345L))

        assertNull(result.contact)
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

    @Test fun `a model still loading does not hold up the message, and its verdict is recorded later`() = runTest {
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        val load = BlockingLoad(FakeSpamClassifier.alwaysSpam(2.0))

        // The load does not finish until released: the old timeout waited for it.
        val result = SmsIngressUseCase(fake, load.classifier, db).handle(RawMessage("+98912", "win prize now", 12345L))

        assertEquals(1L, result.messageId)
        assertNull(result.senderState)
        assertEquals(NotificationDecision.NORMAL, result.notificationDecision)
        assertNull(db.messageVerdictDao.getByMessageId(1L))

        load.release.countDown()
        awaitReal { db.messageVerdictDao.getByMessageId(1L) != null }
        assertTrue(db.messageVerdictDao.getByMessageId(1L)!!.isSpam)
        assertEquals(ThreadSpamState.SUSPECTED, db.senderStateDao.getByAddress("+98912")!!.state)
        // Suspected spam stays unread in the inbox, as when classified in time.
        assertEquals(emptyList<Pair<Long, Boolean>>(), fake.updatedMessageReads)
    }

    @Test fun `a sender already in Spam stays silent while the model loads`() = runTest {
        val fake = telephony()
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.SPAM, spamCount = 11))
        val load = BlockingLoad(FakeSpamClassifier.alwaysSpam(2.0))

        val result = SmsIngressUseCase(fake, load.classifier, db).handle(RawMessage("+98912", "win prize now", 12345L))

        assertEquals(NotificationDecision.NONE, result.notificationDecision)
        load.release.countDown()
        awaitReal { db.messageVerdictDao.getByMessageId(1L) != null }
        assertEquals(12, db.senderStateDao.getByAddress("+98912")!!.spamCount)
        // In Spam once the verdict lands, so marked read like any message there.
        assertEquals(listOf(1L to true), fake.updatedMessageReads)
    }

    @Test fun `a failed classifier announces as the sender is routed`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.SPAM, spamCount = 3))
        val failing = FakeSpamClassifier(verdictFor = { throw RuntimeException("model fail") })

        val result = SmsIngressUseCase(telephony(), failing, db).handle(RawMessage("+98912", "hello", 1L))

        assertEquals(NotificationDecision.NONE, result.notificationDecision)
        // Nothing counted without a verdict.
        assertEquals(3, db.senderStateDao.getByAddress("+98912")!!.spamCount)
    }

    @Test fun `with spam protection off an unclassified message notifies`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.SPAM, spamCount = 3))
        val failing = FakeSpamClassifier(verdictFor = { throw RuntimeException("model fail") })

        val result = SmsIngressUseCase(telephony(), failing, db, isSpamProtectionEnabled = { false })
            .handle(RawMessage("+98912", "hello", 1L))

        assertEquals(NotificationDecision.NORMAL, result.notificationDecision)
    }

    @Test fun `the message is stored before the block lists are read`() = runTest {
        val fake = telephony()
        var storedFirst = false
        val blocklist = object : BlocklistDao by InMemoryBlocklistDao() {
            override suspend fun findByAddress(address: String): BlocklistEntity? {
                storedFirst = fake.insertedInbox.isNotEmpty()
                return null
            }
        }

        SmsIngressUseCase(fake, FakeSpamClassifier.alwaysHam(), NoSpamDatabase(blocklistDao = blocklist))
            .handle(RawMessage("+98912", "hello", 1L))

        assertTrue(storedFirst)
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

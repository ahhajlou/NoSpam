package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpamBackfillUseCase.rescanAll(): forced re-evaluation, respecting pinned
 * per-message labels and sender overrides, and cancellation mid-rescan. Split
 * out of the original 21-test SpamBackfillUseCaseTest by scenario group
 * (Wave 2A task brief).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpamBackfillRescanTest {

    private fun inbox(id: Long, address: String, body: String, date: Long, threadId: Long = id): Message =
        Message(MessageId(id), ThreadId(threadId), address, body, date, MessageType.INBOX, read = false)

    private fun recentAgo(secondsAgo: Long): Long = System.currentTimeMillis() - secondsAgo * 1000L

    private fun classifierWhere(isSpam: (RawMessage) -> Boolean) = FakeSpamClassifier(
        verdictFor = { if (isSpam(it)) SpamVerdict(SpamLabel.SPAM, 2.0) else SpamVerdict(SpamLabel.HAM, -1.0) }
    )

    private fun useCase(
        db: NoSpamDatabase,
        telephony: FakeTelephonyDataSource,
        classifier: FakeSpamClassifier,
        writer: SpamStateWriter = SpamStateWriter(db.senderStateDao),
        scope: TestScope,
    ) = SpamBackfillUseCase(
        telephony = telephony,
        classifier = classifier,
        db = db,
        spamStateWriter = writer,
        isSpamProtectionEnabled = { true },
        externalScope = scope,
    )

    @Test fun `rescanAll re-evaluates already classified messages and preserves createdAt`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "free gift", recentAgo(200)))
        }
        var spam = false
        val classifier = FakeSpamClassifier(verdictFor = { if (spam) SpamVerdict(SpamLabel.SPAM, 2.0) else SpamVerdict(SpamLabel.HAM, -1.0) })
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()
        assertEquals(ThreadSpamState.CLEAN, db.senderStateDao.getByAddress("+98912")!!.state)
        assertTrue(!db.messageVerdictDao.getByMessageId(1)!!.isSpam)
        val originalCreatedAt = db.messageVerdictDao.getByMessageId(1)!!.createdAt
        assertEquals(1, classifier.callCount)

        // The model/preprocessor improved and now votes spam -- re-check everything.
        spam = true
        backfill.rescanAll()
        advanceUntilIdle()

        val verdict = db.messageVerdictDao.getByMessageId(1)!!
        assertTrue(verdict.isSpam)
        assertEquals(2.0, verdict.score, 0.0)
        assertEquals(originalCreatedAt, verdict.createdAt)
        // Ham history is retained (CLAUDE.md §15): one spam on a CLEAN sender -> MIXED.
        assertEquals(ThreadSpamState.MIXED, db.senderStateDao.getByAddress("+98912")!!.state)
        assertEquals(2, classifier.callCount)
    }

    @Test fun `rescanAll never overwrites a user-pinned verdict`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "free gift", recentAgo(200)))
        }
        val classifier = classifierWhere { true }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()
        assertTrue(db.messageVerdictDao.getByMessageId(1)!!.isSpam)
        // User pins the message as "not spam" (per-message label).
        db.messageVerdictDao.updateUserLabel(1, false)
        assertEquals(1, classifier.callCount)

        backfill.rescanAll()
        advanceUntilIdle()

        // Pinned row is untouched: no reclassify, label preserved.
        assertEquals(1, classifier.callCount)
        val verdict = db.messageVerdictDao.getByMessageId(1)!!
        assertEquals(false, verdict.userLabel)
        assertTrue(verdict.isSpam)
    }

    @Test fun `rescanAll skips senders with a user override`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "free gift", recentAgo(200)))
        }
        val classifier = classifierWhere { false }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()
        assertEquals(1, classifier.callCount)
        // User trusts the sender even though the model would now call it spam.
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.TRUSTED, isUserOverride = true))
        val originalVerdict = db.messageVerdictDao.getByMessageId(1)!!

        backfill.rescanAll()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Done, backfill.status.value)
        assertEquals(1, classifier.callCount) // whole sender skipped, votes too
        assertEquals(ThreadSpamState.TRUSTED, db.senderStateDao.getByAddress("+98912")!!.state)
        val verdict = db.messageVerdictDao.getByMessageId(1)!!
        assertEquals(originalVerdict.isSpam, verdict.isSpam)
        assertEquals(originalVerdict.createdAt, verdict.createdAt)
    }

    @Test fun `rescanAll demotes an auto-SPAM sender the corrected model now calls ham`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            // E.g. the IELTS payment receipt that the broken preprocessor misfiled as spam.
            allMessages.add(inbox(1, "+98912", "IELTS On Computer payment receipt", recentAgo(200)))
        }
        // Old model: false positive -> auto SPAM.
        val backfill = useCase(db, telephony, classifierWhere { true }, scope = this)
        backfill.ensureStarted()
        advanceUntilIdle()
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertTrue(db.messageVerdictDao.getByMessageId(1)!!.isSpam)

        // Preprocessing fixed: the same message now classifies as ham.
        val recheck = useCase(db, telephony, classifierWhere { false }, scope = this)
        recheck.rescanAll()
        advanceUntilIdle()

        // Sticky SPAM must not trap an auto-classified sender across a
        // user-initiated model-fix rescan (user overrides stay frozen elsewhere).
        assertEquals(ThreadSpamState.CLEAN, db.senderStateDao.getByAddress("+98912")!!.state)
        assertTrue(!db.messageVerdictDao.getByMessageId(1)!!.isSpam)
    }

    @Test fun `rescanAll can be cancelled mid re-evaluation and stays fail-open`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll(listOf(inbox(1, "+98912", "spam a", recentAgo(300)), inbox(2, "+98999", "spam b", recentAgo(200))))
        }
        // Pre-seed verdicts so both senders are fully classified (force mode re-runs them anyway).
        db.messageVerdictDao.insert(MessageVerdictEntity(1, 1, "+98912", true, 2.0))
        db.messageVerdictDao.insert(MessageVerdictEntity(2, 2, "+98999", true, 2.0))
        var holder: SpamBackfillUseCase? = null
        val classifier = FakeSpamClassifier(
            verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) },
            onClassify = { holder?.cancel() },
        )
        val backfill = useCase(db, telephony, classifier, scope = this)
        holder = backfill

        backfill.rescanAll()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Cancelled, backfill.status.value)
        // Fail-open: the aborted pass writes nothing.
        assertNull(db.senderStateDao.getByAddress("+98912"))
        assertNull(db.senderStateDao.getByAddress("+98999"))
    }
}

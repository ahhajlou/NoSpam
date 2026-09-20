// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
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
import org.junit.Test

/**
 * SpamBackfillUseCase: resuming a partial scan, concurrent-ingress counter
 * merging, and cancellation. Split out of the original 21-test
 * SpamBackfillUseCaseTest by scenario group (Wave 2A task brief).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpamBackfillResumeAndConcurrencyTest {

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

    @Test fun `partial completion resumes without double counting`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll(listOf(inbox(1, "+98912", "spam a", recentAgo(200)), inbox(2, "+98912", "spam b", recentAgo(100))))
        }
        val classifier = classifierWhere { true }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertEquals(2, classifier.callCount)

        // A third message arrives; scan re-runs.
        telephony.allMessages.add(inbox(3, "+98912", "spam c", recentAgo(50)))
        backfill.ensureStarted()
        advanceUntilIdle()

        // Only the new message was classified; sticky SPAM keeps its single count.
        assertEquals(3, classifier.callCount)
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        assertEquals(1, db.senderStateDao.getByAddress("+98912")!!.spamCount)
        org.junit.Assert.assertNotNull(db.messageVerdictDao.getByMessageId(3))
    }

    @Test fun `concurrently ingressed message count is merged not clobbered`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val writer = SpamStateWriter(db.senderStateDao)
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "old spam", recentAgo(200)))
        }
        // While the backfill classifies the old message, a NEW message from the same
        // sender ingresses through the shared writer (simulated inside onClassify).
        val classifier = FakeSpamClassifier(
            verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) },
            onClassify = {
                if (db.senderStateDao.getByAddress("+98912") == null) {
                    writer.upsertIfNotOverridden("+98912") { SenderStateEntity("+98912", ThreadSpamState.SPAM, spamCount = 1) }
                }
            },
        )
        val backfill = useCase(db, telephony, classifier, writer = writer, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        // Backfill aggregates 1 for the old message; the in-flight ingress adds 1 more.
        assertEquals(2, db.senderStateDao.getByAddress("+98912")!!.spamCount)
    }

    @Test fun `cancel stops between senders and leaves them unclassified`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll(
                listOf(
                    inbox(1, "+98912", "spam a", recentAgo(300)),
                    inbox(2, "+98912", "spam b", recentAgo(200)),
                    inbox(3, "+98999", "spam c", recentAgo(150)),
                    inbox(4, "+98999", "spam d", recentAgo(100)),
                )
            )
        }
        var holder: SpamBackfillUseCase? = null
        val classifier = FakeSpamClassifier(
            verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) },
            onClassify = { holder?.cancel() },
        )
        val backfill = useCase(db, telephony, classifier, scope = this)
        holder = backfill

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Cancelled, backfill.status.value)
        // Neither sender was persisted (fail-open).
        assertNull(db.senderStateDao.getByAddress("+98912"))
        assertNull(db.senderStateDao.getByAddress("+98999"))
    }

    @Test fun `a cancel arriving while idle does not abort the next scan`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "win prize now", recentAgo(60)))
        }
        val backfill = useCase(db, telephony, classifierWhere { true }, scope = this)

        // Cancel with nothing running -- a tap on a stale progress notification.
        backfill.cancel()

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Done, backfill.status.value)
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
    }

    @Test fun `security exception aborts as Failed`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            getAllMessagesError = SecurityException("no permission")
        }
        val backfill = useCase(db, telephony, classifierWhere { false }, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(BackfillStatus.Failed, backfill.status.value)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpamBackfillUseCase.status progress reporting: start tick, intermediate
 * ticks, throttling on large inboxes, and the final tick before completion.
 * Split out of the original 21-test SpamBackfillUseCaseTest by scenario group
 * (Wave 2A task brief).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpamBackfillProgressTest {

    private fun inbox(id: Long, address: String, body: String, date: Long, threadId: Long = id): Message =
        Message(MessageId(id), ThreadId(threadId), address, body, date, MessageType.INBOX, read = false)

    private fun recentAgo(secondsAgo: Long): Long = System.currentTimeMillis() - secondsAgo * 1000L

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

    @Test fun `running 0 of total is emitted the moment the scan starts`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "spam a", recentAgo(60)))
        }
        // Yield on every classify so the conflation-prone StateFlow gets a scheduler
        // tick per emission and the collector can observe intermediate values.
        val classifier = FakeSpamClassifier(verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) }, onClassify = { yield() })
        val backfill = useCase(db, telephony, classifier, scope = this)

        val statuses = mutableListOf<BackfillStatus>()
        val collector = launch { backfill.status.collect { statuses.add(it) } }
        backfill.ensureStarted()
        advanceUntilIdle()
        collector.cancel()

        assertTrue("start tick missing: $statuses", statuses.contains(BackfillStatus.Running(0, 1)))
        assertEquals(BackfillStatus.Done, statuses.last())
    }

    @Test fun `small inbox still reports intermediate progress`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll((1L..37L).map { inbox(it, "+98912", "msg $it", recentAgo(37 - it + 60)) })
        }
        val classifier = FakeSpamClassifier(verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) }, onClassify = { yield() })
        val backfill = useCase(db, telephony, classifier, scope = this)

        val statuses = mutableListOf<BackfillStatus>()
        val collector = launch { backfill.status.collect { statuses.add(it) } }
        backfill.ensureStarted()
        advanceUntilIdle()
        collector.cancel()

        val running = statuses.filterIsInstance<BackfillStatus.Running>()
        // step = max(1, 37/100) = 1 -> every processed message reports a tick.
        assertTrue("start tick missing: $running", running.any { it.processed == 0 && it.total == 37 })
        assertTrue("intermediate ticks missing: $running", running.any { it.processed in 1..36 })
        assertEquals(BackfillStatus.Done, statuses.last())
    }

    @Test fun `large inbox progress updates are throttled`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll((1L..1000L).map { inbox(it, "sender${it % 50}", "body $it", recentAgo(60)) })
        }
        val classifier = FakeSpamClassifier(verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) }, onClassify = { yield() })
        val backfill = useCase(db, telephony, classifier, scope = this)

        val statuses = mutableListOf<BackfillStatus>()
        val collector = launch { backfill.status.collect { statuses.add(it) } }
        backfill.ensureStarted()
        advanceUntilIdle()
        collector.cancel()

        val running = statuses.filterIsInstance<BackfillStatus.Running>()
        // step = max(1, 1000/100) = 10 -> ~100 ticks, not one per message.
        assertTrue("got ${running.size} Running updates for 1000 messages", running.size in 2..102)
        assertEquals(BackfillStatus.Done, statuses.last())
    }

    @Test fun `the final processed tick is published before completion`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll((1L..37L).map { inbox(it, "+98912", "msg $it", recentAgo(37 - it + 60)) })
        }
        val writer = SpamStateWriter(db.senderStateDao)
        val classifier = FakeSpamClassifier(verdictFor = { SpamVerdict(SpamLabel.SPAM, 2.0) }, onClassify = { yield() })
        val backfill = useCase(db, telephony, classifier, writer = writer, scope = this)

        // Hold the single-writer lock so the scan parks on its final flush -- that
        // is the exact moment the last statusProgress tick has already been set.
        writer.withSpamStateLock {
            backfill.ensureStarted()
            advanceUntilIdle()
            assertEquals(BackfillStatus.Running(37, 37), backfill.status.value)
        }
        // Lock released: the scan flushes and finishes.
        advanceUntilIdle()
        assertEquals(BackfillStatus.Done, backfill.status.value)
    }
}

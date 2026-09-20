// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ContactEntry
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpamBackfillUseCase: per-sender grading rules (new sender, graduation
 * threshold, contact/outbound protection, user overrides, retention).
 * Split out of the original 21-test SpamBackfillUseCaseTest by scenario group
 * (Wave 2A task brief).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpamBackfillGradingTest {

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
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "win prize now", recentAgo(60)))
        }
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
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll(
                listOf(
                    inbox(1, "+989111111111", "hello", recentAgo(500)),
                    inbox(2, "+989111111111", "spam1", recentAgo(400)),
                    inbox(3, "+989111111111", "spam2", recentAgo(300)),
                    inbox(4, "+989111111111", "spam3", recentAgo(200)),
                    inbox(5, "+989111111111", "spam4", recentAgo(100)),
                )
            )
        }
        val classifier = classifierWhere { it.body != "hello" }
        val backfill = useCase(db, telephony, classifier, scope = this)

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
        // 1 ham + 6 spams (brutal) -- still only MIXED because the user has replied
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll((1L..7L).map { inbox(it, address, if (it == 1L) "hi" else "spam$it", recentAgo(700 - it * 60)) })
            outboundAddresses += address
        }
        val classifier = classifierWhere { it.body != "hi" }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(ThreadSpamState.MIXED, db.senderStateDao.getByAddress(address)!!.state)
    }

    @Test fun `contact sender via contacts list never promotes to SPAM`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val address = "+989333333333"
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll((1L..7L).map { inbox(it, address, "spam$it", recentAgo(700 - it * 60)) })
            contactEntries.add(ContactEntry(1L, "Ali", address, address))
        }
        val classifier = classifierWhere { true }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(ThreadSpamState.MIXED, db.senderStateDao.getByAddress(address)!!.state)
    }

    @Test fun `user override is never touched`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.TRUSTED, isUserOverride = true))
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "win prize now", recentAgo(60)))
        }
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

    @Test fun `spam protection off forces CLEAN but still records verdict evidence`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.add(inbox(1, "+98912", "win prize", recentAgo(60)))
        }
        val backfill = useCase(db, telephony, classifierWhere { true }, protectionEnabled = false, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        assertEquals(ThreadSpamState.CLEAN, db.senderStateDao.getByAddress("+98912")!!.state)
        val verdict = db.messageVerdictDao.getByMessageId(1)
        assertNotNull(verdict)
        assertTrue(verdict!!.isSpam)
    }

    @Test fun `old spam keeps a verdict row but old ham does not`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val oldDate = System.currentTimeMillis() - 60L * 24L * 60L * 60L * 1000L
        val telephony = FakeTelephonyDataSource().apply {
            allMessages.addAll(
                listOf(
                    inbox(1, "+98912", "ancient spam", oldDate),
                    inbox(3, "+98912", "ancient ham", oldDate),
                    inbox(2, "+98912", "recent ham", System.currentTimeMillis()),
                )
            )
        }
        val classifier = classifierWhere { it.body == "ancient spam" }
        val backfill = useCase(db, telephony, classifier, scope = this)

        backfill.ensureStarted()
        advanceUntilIdle()

        // Old spam voted -> SPAM (sticky, the recent ham can't rescue).
        assertEquals(ThreadSpamState.SPAM, db.senderStateDao.getByAddress("+98912")!!.state)
        // Old spam keeps a per-message row so the "Suspected spam" marker can render forever.
        val oldSpam = db.messageVerdictDao.getByMessageId(1)
        assertNotNull(oldSpam)
        assertTrue(oldSpam!!.isSpam)
        // Old ham never gets a row (would be pruned immediately); recent ham still does.
        assertNull(db.messageVerdictDao.getByMessageId(3))
        assertNotNull(db.messageVerdictDao.getByMessageId(2))
    }
}

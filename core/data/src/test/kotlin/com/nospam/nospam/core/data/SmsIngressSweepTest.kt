// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.BlockingModelLoad
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** [SmsIngressUseCase.classifyMissed]: recent received messages that ingress left without a verdict. */
class SmsIngressSweepTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60L * 1000L

    private fun message(
        id: Long,
        date: Long = now - hour,
        address: String = "+98912",
        type: MessageType = MessageType.INBOX,
        threadId: Long = 7L,
    ) = Message(MessageId(id), ThreadId(threadId), address, "win a prize", date, type, read = false)

    private fun telephony(vararg messages: Message) = FakeTelephonyDataSource().apply { allMessages += messages }

    @Test fun `a message without a verdict is routed as ingress would have`() = runTest {
        val fake = telephony(message(10))
        val db = NoSpamDatabase.inMemory()
        val classifier = FakeSpamClassifier.alwaysSpam(2.0)

        val recorded = SmsIngressUseCase(fake, classifier, db).classifyMissed(now)

        assertEquals(1, recorded)
        assertTrue(db.messageVerdictDao.getByMessageId(10L)!!.isSpam)
        assertEquals(ThreadSpamState.SUSPECTED, db.senderStateDao.getByAddress("+98912")!!.state)
        // Nothing inserted: the message is already in the provider.
        assertTrue(fake.insertedInbox.isEmpty())
    }

    @Test fun `a sender in Spam gets the missed message counted and marked read`() = runTest {
        val fake = telephony(message(10))
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+98912", ThreadSpamState.SPAM, spamCount = 11))

        SmsIngressUseCase(fake, FakeSpamClassifier.alwaysSpam(2.0), db).classifyMissed(now)

        assertEquals(12, db.senderStateDao.getByAddress("+98912")!!.spamCount)
        assertEquals(listOf(10L to true), fake.updatedMessageReads)
    }

    @Test fun `running again counts nothing twice`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony(message(10)), FakeSpamClassifier.alwaysSpam(2.0), db)

        useCase.classifyMissed(now)
        val second = useCase.classifyMissed(now)

        assertEquals(0, second)
        assertEquals(1, db.senderStateDao.getByAddress("+98912")!!.spamCount)
    }

    @Test fun `only received messages without a verdict inside the window are taken`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.messageVerdictDao.insert(MessageVerdictEntity(1L, 7L, "+98912", isSpam = true, score = 1.0, createdAt = now - hour))
        val fake = telephony(
            message(1), // already has a verdict
            message(2, type = MessageType.SENT),
            // Older than the window: may have been counted, its ham verdict pruned.
            message(3, date = now - SmsIngressUseCase.MISSED_WINDOW_MS - 1),
            // Younger than the grace period: ingress may still be on it.
            message(4, date = now - SmsIngressUseCase.MISSED_GRACE_MS + 1),
        )
        val classifier = FakeSpamClassifier.alwaysSpam(2.0)

        val recorded = SmsIngressUseCase(fake, classifier, db).classifyMissed(now)

        assertEquals(0, recorded)
        assertEquals(0, classifier.callCount)
        assertNull(db.senderStateDao.getByAddress("+98912"))
    }

    @Test fun `saved contacts are left without a verdict`() = runTest {
        val fake = telephony(message(10)).apply { contacts["+98912"] = Participant(address = "+98912", displayName = "Bank Mellat") }
        val db = NoSpamDatabase.inMemory()
        val classifier = FakeSpamClassifier.alwaysSpam(2.0)

        val recorded = SmsIngressUseCase(fake, classifier, db).classifyMissed(now)

        assertEquals(0, recorded)
        assertEquals(0, classifier.callCount)
        assertNull(db.messageVerdictDao.getByMessageId(10L))
    }

    @Test fun `a blocked sender's message is recorded as blocked`() = runTest {
        val fake = telephony(message(10)).apply { systemBlocked += "+98912" }
        val db = NoSpamDatabase.inMemory()

        SmsIngressUseCase(fake, FakeSpamClassifier.alwaysHam(), db).classifyMissed(now)

        assertEquals(ThreadSpamState.BLOCKED, db.senderStateDao.getByAddress("+98912")!!.state)
        assertTrue(db.messageVerdictDao.getByMessageId(10L)!!.isSpam)
        assertEquals(listOf(10L to true), fake.updatedMessageReads)
    }

    @Test fun `a failed classifier records nothing`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val failing = FakeSpamClassifier(verdictFor = { throw RuntimeException("model fail") })

        val recorded = SmsIngressUseCase(telephony(message(10)), failing, db).classifyMissed(now)

        assertEquals(0, recorded)
        assertNull(db.messageVerdictDao.getByMessageId(10L))
    }

    @Test fun `a message still waiting for its late verdict is left to ingress`() = runTest {
        val fake = FakeTelephonyDataSource().apply { nextThreadId = 7L }
        val db = NoSpamDatabase.inMemory()
        val load = BlockingModelLoad(FakeSpamClassifier.alwaysSpam(2.0))
        val useCase = SmsIngressUseCase(fake, load.classifier, db)
        // Missed the budget: ingress records it once the model loads.
        val result = useCase.handle(RawMessage("+98912", "win a prize", now - hour))
        fake.allMessages += message(result.messageId!!, date = now - hour)

        assertEquals(0, useCase.classifyMissed(now))

        load.release.countDown()
        result.lateDecision!!.await()
        assertEquals(1, db.senderStateDao.getByAddress("+98912")!!.spamCount)
        // Recorded now, so a later sweep leaves it alone too.
        assertEquals(0, useCase.classifyMissed(now))
        assertEquals(1, db.senderStateDao.getByAddress("+98912")!!.spamCount)
    }

    @Test fun `the window stays inside the pruning of ham verdicts`() {
        val retention = SpamRepository.SPAM_RETENTION_DAYS * 24L * 60L * 60L * 1000L
        assertTrue(SmsIngressUseCase.MISSED_WINDOW_MS < retention)
    }
}

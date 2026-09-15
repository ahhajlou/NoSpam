package com.nospam.nospam.core.testing

import com.nospam.nospam.core.common.SmsPermissions
import com.nospam.nospam.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * These guard the fakes themselves. They matter more than they look: every
 * other module's tests substitute these for the real `SpamClassifier` and
 * `TelephonyDataSource`, so a fake that quietly stops honouring its interface
 * would make those suites pass for the wrong reason.
 */
class FakesTest {
    @Test fun `FakePermissionChecker grant and revoke`() {
        val checker = FakePermissionChecker()
        assertFalse(checker.hasPermission(SmsPermissions.READ_SMS))
        val granted = checker.grant(SmsPermissions.READ_SMS)
        assertTrue(granted.hasPermission(SmsPermissions.READ_SMS))
        assertTrue(granted.hasPermissions(listOf(SmsPermissions.READ_SMS)))
        assertFalse(granted.hasPermissions(listOf(SmsPermissions.READ_SMS, SmsPermissions.SEND_SMS)))
        assertFalse(granted.revoke(SmsPermissions.READ_SMS).hasPermission(SmsPermissions.READ_SMS))
    }

    @Test fun `FakeSpamClassifier records the message it was given`() = runTest {
        val classifier = FakeSpamClassifier()
        assertEquals(0, classifier.callCount)
        val verdict = classifier.classify(TestData.sampleRawHam)
        assertEquals(TestData.sampleRawHam, classifier.lastMessage)
        assertEquals(1, classifier.callCount)
        assertFalse(verdict.isSpam)
    }

    @Test fun `FakeSpamClassifier variants return the verdict they promise`() = runTest {
        assertTrue(FakeSpamClassifier.alwaysSpam().classify(TestData.sampleRawHam).isSpam)
        assertFalse(FakeSpamClassifier.alwaysHam().classify(TestData.sampleRawSpam).isSpam)
    }

    @Test fun `FakeSpamClassifier routes classifyText through the same rule`() = runTest {
        val classifier = FakeSpamClassifier.spamWhen { it.contains("prize") }
        assertTrue(classifier.classifyText("you won a prize").isSpam)
        assertFalse(classifier.classifyText("see you at noon").isSpam)
    }

    @Test fun `FakeTelephonyDataSource emits seeded conversations`() = runTest {
        val fake = FakeTelephonyDataSource(listOf(TestData.sampleConversation))
        assertEquals(1, fake.observeConversations().first().size)
        assertEquals(1, fake.getConversations().size)

        fake.emitConversations(emptyList())
        assertEquals(0, fake.observeConversations().first().size)
    }

    @Test fun `FakeTelephonyDataSource records inbox writes with their read flag`() = runTest {
        val fake = FakeTelephonyDataSource()
        fake.insertInboxMessage("+989121234567", "hello", 1L, read = false)
        fake.insertInboxMessage("1000", "win a prize", 2L, read = true)

        assertEquals(2, fake.insertedInbox.size)
        assertEquals(Triple("+989121234567", "hello", false), fake.insertedInbox[0])
        assertEquals(Triple("1000", "win a prize", true), fake.insertedInbox[1])
    }

    @Test fun `FakeTelephonyDataSource records deletions`() = runTest {
        val fake = FakeTelephonyDataSource()
        fake.deleteConversation(ThreadId(7))
        assertEquals(listOf(7L), fake.deletedThreadIds)
    }

    @Test fun `FakeTelephonyDataSource paginates backwards from a message id`() = runTest {
        val fake = FakeTelephonyDataSource()
        val messages = (1L..5L).map {
            Message(
                id = MessageId(it),
                threadId = ThreadId(1),
                address = "+989121234567",
                body = "m$it",
                date = it,
                type = MessageType.INBOX,
                read = true,
            )
        }
        fake.emitMessages(ThreadId(1), messages)

        val newest = fake.getMessages(ThreadId(1), limit = 2)
        assertEquals(listOf("m4", "m5"), newest.map { it.body })

        val older = fake.getMessages(ThreadId(1), limit = 2, beforeId = 4L)
        assertEquals(listOf("m2", "m3"), older.map { it.body })
    }

    @Test fun `FakeTelephonyDataSource reports only the addresses it was told about`() = runTest {
        val fake = FakeTelephonyDataSource()
        fake.systemBlocked += "+989121234567"
        fake.outboundAddresses += "+989350000000"

        assertTrue(fake.isSystemBlocked("+989121234567"))
        assertFalse(fake.isSystemBlocked("1000"))
        assertEquals(setOf("+989350000000"), fake.getOutboundSenderAddresses())
        assertNull(fake.lookupContact("1000"))
    }
}

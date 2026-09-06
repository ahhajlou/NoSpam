package com.nospam.nospam.core.testing

import com.nospam.nospam.core.common.SmsPermissions
import com.nospam.nospam.core.model.*
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun `FakeSpamClassifier records calls and variants`() {
        val classifier = FakeSpamClassifier()
        assertEquals(0, classifier.callCount)
        val verdict = classifier.classify(TestData.sampleRawHam)
        assertEquals(TestData.sampleRawHam, classifier.lastMessage)
        assertEquals(1, classifier.callCount)
        assertFalse(verdict.isSpam)
        assertTrue(classifier.returnsSpam().classify(TestData.sampleRawHam).isSpam)
        assertFalse(classifier.returnsHam().classify(TestData.sampleRawSpam).isSpam)
    }

    @Test fun `FakeTelephonyDataSource seeds and filters`() {
        val fake = FakeTelephonyDataSource()
        val starred = TestData.sampleConversation.copy(isStarred = true)
        val unread = TestData.sampleConversation.copy(read = false)
        val read = TestData.sampleConversation.copy(read = true)
        fake.seed(listOf(starred, unread, read))
        assertEquals(3, fake.fakeConversations(ConversationFilter.ALL).size)
        assertEquals(2, fake.fakeConversations(ConversationFilter.UNREAD).size)
        assertEquals(1, fake.fakeConversations(ConversationFilter.STARRED).size)
    }
}

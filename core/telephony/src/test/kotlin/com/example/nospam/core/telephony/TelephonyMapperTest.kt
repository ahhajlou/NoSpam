package com.example.nospam.core.telephony

import android.telephony.SubscriptionManager
import com.example.nospam.core.model.TelephonyConstants
import com.example.nospam.core.telephony.service.HeadlessSmsSendService
import org.junit.Assert.*
import org.junit.Test

class TelephonyMapperTest {
    @Test fun `constants are correct`() {
        assertEquals("android.provider.Telephony.SMS_DELIVER", TelephonyConstants.ACTION_SMS_DELIVER)
        assertEquals("android.permission.BROADCAST_SMS", TelephonyConstants.PERMISSION_BROADCAST_SMS)
    }

    @Test fun `TelephonyDataSource interface exists`() {
        assertTrue(TelephonyDataSource::class.java.isInterface)
    }

    @Test fun `toConversation groups by latest message`() {
        val thread = com.example.nospam.core.model.ThreadId(5L)
        val messages = listOf(
            com.example.nospam.core.model.Message(
                com.example.nospam.core.model.MessageId(1), thread,
                "+1555", "older", 1000L,
                com.example.nospam.core.model.MessageType.INBOX, read = true,
            ),
            com.example.nospam.core.model.Message(
                com.example.nospam.core.model.MessageId(2), thread,
                "+1555", "latest unread", 2000L,
                com.example.nospam.core.model.MessageType.INBOX, read = false,
            ),
        )
        val conv = TelephonyMapper.toConversation(thread, messages)
        assertEquals("latest unread", conv.snippet)
        assertEquals(2000L, conv.date)
        assertEquals(2, conv.messageCount)
        assertFalse(conv.read)
        assertEquals("+1555", conv.participants.first().address)
    }

    @Test fun `reply text prefers RemoteInput over extras`() {
        assertEquals(
            "hello",
            HeadlessSmsSendService.pickReplyText("hello", "extra", "sms_body")
        )
        assertEquals(
            "extra",
            HeadlessSmsSendService.pickReplyText(null, "extra", "sms_body")
        )
        assertNull(HeadlessSmsSendService.pickReplyText(null, null, "  "))
    }

    @Test fun `subscription id normalized`() {
        assertEquals(
            3,
            HeadlessSmsSendService.normalizeSubscriptionId(true, 3)
        )
        assertNull(
            HeadlessSmsSendService.normalizeSubscriptionId(
                true,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )
        )
        assertNull(HeadlessSmsSendService.normalizeSubscriptionId(false, 3))
    }
}

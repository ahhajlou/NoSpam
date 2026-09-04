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

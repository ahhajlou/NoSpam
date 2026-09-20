// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ContentValues/Cursor mapping — needs a device, see TelephonyInstrumentedTest. */
@RunWith(AndroidJUnit4::class)
class TelephonyMapperDeviceTest {
    @Test fun buildMessageValues_round_trip() {
        val cv = TelephonyMapper.buildMessageValues("+98912", "hello", 12345L, 0)
        assertEquals("+98912", cv.getAsString(Telephony.Sms.ADDRESS))
        assertEquals("hello", cv.getAsString(Telephony.Sms.BODY))
        assertEquals(12345L, cv.getAsLong(Telephony.Sms.DATE))
        assertEquals(0, cv.getAsInteger(Telephony.Sms.READ))
        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_INBOX,
            cv.getAsInteger(Telephony.Sms.TYPE),
        )
    }

    @Test fun buildSentValues_marks_read_sent() {
        val cv = TelephonyMapper.buildSentValues("+98912", "reply", 99L, subscriptionId = 1)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, cv.getAsInteger(Telephony.Sms.TYPE))
        assertEquals(1, cv.getAsInteger(Telephony.Sms.READ))
        assertEquals(1, cv.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID))
    }
}

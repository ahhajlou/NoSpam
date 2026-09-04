package com.example.nospam.core.telephony

import com.example.nospam.core.model.TelephonyConstants
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
}

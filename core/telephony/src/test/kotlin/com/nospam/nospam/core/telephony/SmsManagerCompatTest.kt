package com.nospam.nospam.core.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SmsManagerCompat.resolveSmsManager: a priority-5 target per the task brief
 * with no prior dedicated test file (core-telephony.md). Exercises both the
 * modern (API 31+, createForSubscriptionId) and legacy branches.
 */
@RunWith(RobolectricTestRunner::class)
class SmsManagerCompatTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Config(sdk = [31])
    @Test fun `resolves a manager on the modern API with no explicit subscription`() {
        assertNotNull(context.resolveSmsManager(null))
    }

    @Config(sdk = [31])
    @Test fun `resolves a manager on the modern API with an explicit subscription`() {
        assertNotNull(context.resolveSmsManager(subscriptionId = 5))
    }

    @Config(sdk = [26])
    @Test fun `resolves a manager on the legacy API with no explicit subscription`() {
        assertNotNull(context.resolveSmsManager(null))
    }

    @Config(sdk = [26])
    @Test fun `resolves a manager on the legacy API with an explicit subscription`() {
        assertNotNull(context.resolveSmsManager(subscriptionId = 5))
    }
}

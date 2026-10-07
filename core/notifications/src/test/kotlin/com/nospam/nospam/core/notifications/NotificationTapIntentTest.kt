// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.core.model.TelephonyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tapping a message notification starts a fresh task, as Google Messages and
 * AOSP Messaging do. Reusing the existing task broke after process death: the
 * activity came back with its saved back stack (the inbox) and the
 * conversation never opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class NotificationTapIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tapIntents(threadId: Long = 42L, sender: String = "+15551234567"): List<Intent> {
        val notification = NotificationHelper.buildMessageNotification(
            context, threadId = threadId, sender = sender, messageBody = "Hi",
        )
        val shadow = shadowOf(checkNotNull(notification.contentIntent))
        assertTrue("opens an activity", shadow.isActivity)
        return shadow.savedIntents.toList()
    }

    @Test fun `tap clears the task and starts it afresh`() {
        val first = tapIntents().first()
        val required = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_TASK_ON_HOME
        assertEquals(required, first.flags and required)
    }

    @Test fun `tap opens only the main activity, on the notification's thread`() {
        val intents = tapIntents(threadId = 42L)
        assertEquals(1, intents.size)
        val intent = intents.single()
        assertEquals("com.nospam.nospam.MainActivity", intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(42L, intent.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L))
        assertEquals("sms", intent.data?.scheme)
    }

    @Test fun `each conversation keeps its own tap target`() {
        val a = tapIntents(threadId = 1L, sender = "+15550000001").single()
        val b = tapIntents(threadId = 2L, sender = "+15550000002").single()
        assertEquals(1L, a.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L))
        assertEquals(2L, b.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L))
    }
}

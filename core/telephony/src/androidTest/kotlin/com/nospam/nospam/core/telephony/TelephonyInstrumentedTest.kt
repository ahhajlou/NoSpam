// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.notifications.NotificationHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device or emulator (`./gradlew :core:telephony:connectedDebugAndroidTest`).
 *
 * The SMS round-trip test takes the default-SMS role for itself through
 * [SmsRoleRule] (only the default app can write Telephony.Sms) and skips only
 * when the role cannot be taken. It used to skip always, because it waited for
 * someone to assign the role by hand.
 */
@RunWith(AndroidJUnit4::class)
class TelephonyInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
    )

    @get:Rule
    val smsRole = SmsRoleRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test fun sms_insert_and_query_round_trip() = runTest {
        smsRole.require()
        val dataSource = RealTelephonyDataSource(context)
        // A number nobody has: the test deletes this whole conversation at the
        // end. It used the emulator's own number, whose real thread it would
        // have wiped once it actually ran.
        val address = "+15557650077"
        val id = dataSource.insertInboxMessage(address, "instrumented hello", System.currentTimeMillis(), read = false)
        assertNotNull(id)
        val threadId = dataSource.getOrCreateThreadId(address)
        assertTrue(threadId >= 0)
        val messages = dataSource.getMessages(ThreadId(threadId))
        assertTrue(messages.any { it.body == "instrumented hello" })
        dataSource.deleteConversation(ThreadId(threadId))
    }

    @Test fun notification_builds_reply_action() {
        NotificationHelper.createChannels(context)
        val notification = NotificationHelper.buildMessageNotification(
            context = context,
            threadId = 123L,
            sender = "+15551234567",
            messageBody = "hello",
            isSpam = false,
            subscriptionId = null,
        )
        val actions = androidx.core.app.NotificationCompat.getActionCount(notification)
        assertEquals(1, actions)
    }
}

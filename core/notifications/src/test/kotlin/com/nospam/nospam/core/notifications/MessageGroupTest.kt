// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.Manifest
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Message notifications bundle under one expandable summary, as in Google
 * Messages: the conversations are the children and alert, the summary has no
 * text of its own and stays quiet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MessageGroupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun message(isSpam: Boolean = false): Notification =
        NotificationHelper.buildMessageNotification(
            context, threadId = 5L, sender = "+15551234567", messageBody = "Hi", isSpam = isSpam,
        )

    @Test fun `a message joins the messages group and alerts for itself`() {
        val n = message()
        assertEquals(NotificationHelper.GROUP_KEY_MESSAGES, n.group)
        assertFalse(NotificationCompat.isGroupSummary(n))
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, NotificationCompat.getGroupAlertBehavior(n))
    }

    @Test fun `suspected spam stays out of the group`() {
        assertNull(message(isSpam = true).group)
    }

    @Test fun `the summary shares the key, is quiet and carries no text`() {
        val n = NotificationHelper.buildMessageSummary(context)
        assertEquals(NotificationHelper.GROUP_KEY_MESSAGES, n.group)
        assertTrue(NotificationCompat.isGroupSummary(n))
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, NotificationCompat.getGroupAlertBehavior(n))
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(NotificationHelper.CHANNEL_ID_MESSAGES, n.channelId)
        assertNull(n.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(n.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun `tapping the summary opens the app`() {
        assertTrue(NotificationHelper.buildMessageSummary(context).contentIntent != null)
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before fun grantNotifications() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun post(threadId: Long) {
        manager.createNotificationChannel(
            NotificationChannel(NotificationHelper.CHANNEL_ID_MESSAGES, "m", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = NotificationHelper.buildMessageNotification(
            context, threadId = threadId, sender = "+1555000$threadId", messageBody = "Hi",
        )
        manager.notify(NotificationHelper.notificationId(threadId), n)
        NotificationHelper.notifyMessageSummary(context)
    }

    @Test fun `the summary stays while a conversation is left`() {
        post(1L)
        post(2L)
        NotificationHelper.cancelNotification(context, 1L)
        assertEquals(2, manager.activeNotifications.size)
    }

    @Test fun `the summary goes with the last conversation`() {
        post(1L)
        post(2L)
        NotificationHelper.cancelNotification(context, 1L)
        NotificationHelper.cancelNotification(context, 2L)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * After an inline reply the notification is posted again with the reply, as
 * Google Messages does. Android 15+ ignores the app's cancel() on a notification
 * the user replied to until the app posts it again, so canceling left it in
 * the shade for good (reproduced on the API 37 emulator, 2026-10-13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AppendReplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val threadId = 42L

    @Before fun setUp() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
    }

    private fun post(sender: String = "+15551234567") {
        val n = NotificationHelper.buildMessageNotification(
            context, threadId = threadId, sender = sender, messageBody = "Are you free?",
            senderName = "Sara", timestamp = 1_000L,
        )
        manager.notify(NotificationHelper.notificationId(threadId), n)
    }

    private fun active(): Notification =
        manager.activeNotifications.single { it.id == NotificationHelper.notificationId(threadId) }.notification

    private fun styleOf(n: Notification) =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n))

    @Test fun `the reply is added under the user, after the message`() {
        post()
        assertTrue(NotificationHelper.appendReply(context, threadId, "Yes, at 5", timestamp = 2_000L))

        val messages = styleOf(active()).messages
        assertEquals(listOf("Are you free?", "Yes, at 5"), messages.map { it.text.toString() })
        assertEquals("Sara", messages[0].person?.name)
        assertNull("the user's own message has no sender", messages[1].person)
    }

    @Test fun `the repost does not alert again`() {
        post()
        NotificationHelper.appendReply(context, threadId, "Yes")
        assertTrue(active().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun `the repost keeps the reply action, the tap, the channel and the conversation`() {
        post()
        val before = active()
        NotificationHelper.appendReply(context, threadId, "Yes")
        val after = active()

        assertEquals(before.channelId, after.channelId)
        assertEquals(before.shortcutId, after.shortcutId)
        assertEquals(Notification.CATEGORY_MESSAGE, after.category)
        val reply = after.actions.single { !it.remoteInputs.isNullOrEmpty() }
        assertEquals(NotificationHelper.KEY_TEXT_REPLY, reply.remoteInputs.single().resultKey)
        assertEquals("+15551234567", shadowOf(reply.actionIntent).savedIntent.data?.schemeSpecificPart)
        assertNotNull(after.contentIntent)
        assertEquals(
            shadowOf(before.contentIntent).savedIntents.single().data,
            shadowOf(after.contentIntent).savedIntents.single().data,
        )
        assertEquals("You", styleOf(after).user.name)
    }

    @Test fun `a notification already swiped away is not brought back`() {
        assertFalse(NotificationHelper.appendReply(context, threadId, "Yes"))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `another conversation's notification is left alone`() {
        post()
        assertFalse(NotificationHelper.appendReply(context, threadId + 1, "Yes"))
        assertEquals(1, styleOf(active()).messages.size)
    }

    @Test fun `without the notification permission nothing is posted`() {
        post()
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationHelper.appendReply(context, threadId, "Yes"))
        assertEquals(1, styleOf(active()).messages.size)
    }
}

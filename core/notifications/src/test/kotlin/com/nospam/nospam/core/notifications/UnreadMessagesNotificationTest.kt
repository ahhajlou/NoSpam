// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A conversation's notification lists its unread messages, oldest first, with
 * the unread count and a "Mark as read" action, as Google Messages does
 * (checked on the API 37 emulator, 2026-10-23). Until then it showed only the
 * newest message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UnreadMessagesNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun intent(action: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(action), PendingIntent.FLAG_IMMUTABLE)

    private fun build(
        earlier: List<NotificationLine> = emptyList(),
        unreadCount: Int = earlier.size + 1,
        markRead: PendingIntent? = null,
        dismiss: PendingIntent? = null,
    ): Notification = NotificationHelper.buildMessageNotification(
        context, threadId = 5L, sender = "+15551234567", messageBody = "third", timestamp = 3_000L,
        earlierMessages = earlier, unreadCount = unreadCount,
        markReadIntent = markRead, dismissIntent = dismiss,
    )

    private fun texts(n: Notification) =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n))
            .messages.map { it.text.toString() }

    @Test fun `earlier unread messages are listed before the new one`() {
        val n = build(earlier = listOf(NotificationLine("first", 1_000L), NotificationLine("second", 2_000L)))

        assertEquals(listOf("first", "second", "third"), texts(n))
    }

    @Test fun `every message is from the sender, not from you`() {
        val n = build(earlier = listOf(NotificationLine("first", 1_000L)))

        val people = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n))
            .messages.map { it.person?.key }
        assertEquals(listOf("+15551234567", "+15551234567"), people)
    }

    @Test fun `the notification carries the unread count, listed or not`() {
        assertEquals(12, build(unreadCount = 12).number)
        assertEquals(1, build().number)
    }

    @Test fun `mark as read comes first and opens nothing`() {
        val markRead = intent("mark")
        val n = build(markRead = markRead)

        val action = NotificationCompat.getAction(n, 0)!!
        assertEquals("Mark as read", action.title.toString())
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, action.semanticAction)
        assertEquals(false, action.showsUserInterface)
        assertSame(markRead, action.actionIntent)
        assertEquals("Reply", NotificationCompat.getAction(n, 1)!!.title.toString())
    }

    @Test fun `without a mark-read intent only reply is offered`() {
        val n = build()

        assertEquals(1, NotificationCompat.getActionCount(n))
    }

    @Test fun `swiping it away sends the dismiss intent`() {
        val dismiss = intent("dismiss")

        assertSame(dismiss, build(dismiss = dismiss).deleteIntent)
        assertNull(build().deleteIntent)
    }
}

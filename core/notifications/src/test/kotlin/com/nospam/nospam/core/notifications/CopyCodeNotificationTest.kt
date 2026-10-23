// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message with a one-time code offers to copy it, drawn as the chip Android
 * System Intelligence adds to Google Messages' notifications for English codes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CopyCodeNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun intent(action: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(action), PendingIntent.FLAG_IMMUTABLE)

    private fun build(code: String?, copy: PendingIntent?): Notification = NotificationHelper.buildMessageNotification(
        context, threadId = 5L, sender = "+15551234567", messageBody = "Your code is 482913",
        markReadIntent = intent("mark"), oneTimeCode = code, copyCodeIntent = copy,
    )

    private fun titles(n: Notification) =
        (0 until NotificationCompat.getActionCount(n)).map { NotificationCompat.getAction(n, it)!!.title.toString() }

    @Test fun `the code is a chip that copies it and opens nothing`() {
        val copy = intent("copy")
        val n = build("482913", copy)

        val chip = NotificationCompat.getAction(n, 0)!!
        assertEquals("482913", chip.title.toString())
        assertTrue(chip.isContextual)
        assertEquals(false, chip.showsUserInterface)
        assertSame(copy, chip.actionIntent)
        assertEquals(listOf("482913", "Mark as read", "Reply"), titles(n))
    }

    @Test fun `with our chip Android adds none of its own`() {
        assertFalse(NotificationCompat.getAllowSystemGeneratedContextualActions(build("482913", intent("copy"))))
        assertTrue(NotificationCompat.getAllowSystemGeneratedContextualActions(build(null, null)))
    }

    @Config(sdk = [28])
    @Test fun `before Android 10, which has no chips, it is a button that says what it does`() {
        val action = NotificationHelper.copyCodeAction(context, "482913", intent("copy"))

        assertEquals("Copy 482913", action.title.toString())
        assertFalse(action.isContextual)
    }

    @Test fun `without a code there is no copy chip`() {
        assertTrue(titles(build(null, null)).none { it.contains("482913") })
        assertTrue(titles(build(null, intent("copy"))).none { it.contains("482913") })
    }
}

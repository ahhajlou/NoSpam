// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Who a message notification says it is from. A saved contact shows its name
 * and photo, as Google Messages does; until 2026-10-08 every notification
 * showed the bare number. What the notification acts on stays the address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class NotificationSenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun build(
        sender: String = "+15551234567",
        senderName: String? = null,
        senderAvatar: Bitmap? = null,
        threadId: Long = 5L,
    ): Notification = NotificationHelper.buildMessageNotification(
        context, threadId = threadId, sender = sender, messageBody = "Hi",
        senderName = senderName, senderAvatar = senderAvatar,
    )

    private fun senderOf(n: Notification) =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n))
            .messages.single().person!!

    private fun shortcut(threadId: Long) =
        ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == "thread-$threadId" }

    private fun replyIntent(n: Notification) =
        shadowOf(n.actions.single { !it.remoteInputs.isNullOrEmpty() }.actionIntent).savedIntent

    @Test fun `a contact is shown by name, keyed by its address`() {
        val person = senderOf(build(senderName = "Sara"))
        assertEquals("Sara", person.name)
        assertEquals("+15551234567", person.key)
    }

    @Test fun `a stranger is shown by number`() {
        val person = senderOf(build())
        assertEquals("+15551234567", person.name)
        assertNull(person.icon)
    }

    @Test fun `a blank contact name falls back to the number`() {
        assertEquals("+15551234567", senderOf(build(senderName = " ")).name)
    }

    @Test fun `a sender ID keeps its own text`() {
        assertEquals("MCI", senderOf(build(sender = "MCI")).name)
    }

    @Test fun `a contact's photo is the sender's avatar and the shortcut's icon`() {
        val photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val person = senderOf(build(senderName = "Sara", senderAvatar = photo, threadId = 11L))
        assertNotNull(person.icon)
        // ShortcutManager hands shortcuts back without their icons, so read the
        // stored one directly (ShortcutInfo.getIcon is hidden).
        val stored = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single { it.id == "thread-11" }
        assertNotNull(ReflectionHelpers.callInstanceMethod<Icon?>(stored, "getIcon"))
    }

    @Test fun `the conversation shortcut is labelled with the contact's name`() {
        build(senderName = "Sara", threadId = 12L)
        assertEquals("Sara", shortcut(12L).shortLabel)
    }

    @Test fun `the user is the phone's owner, so an inline reply is not filed under the sender`() {
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(build(senderName = "Sara")))
        assertEquals("You", style.user.name)
        assertNull(style.user.key)
    }

    @Test fun `a reply still goes to the number, not the name`() {
        val intent = replyIntent(build(senderName = "Sara"))
        assertEquals("+15551234567", intent.data?.schemeSpecificPart)
    }

    @Test fun `the tap still opens the number's conversation`() {
        val intent = shadowOf(build(senderName = "Sara").contentIntent).savedIntents.single()
        assertEquals("+15551234567", intent.data?.schemeSpecificPart)
    }
}

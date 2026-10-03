// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import org.robolectric.annotation.Config

/**
 * Written from the spec for the "messages" -> "messages_v2" channel migration
 * and the reply action, without reading [NotificationHelper]. Runs against
 * Robolectric's NotificationManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MessagesChannelTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun legacy(importance: Int, configure: NotificationChannel.() -> Unit = {}) {
        val channel = NotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID_MESSAGES, "Messages", importance)
        channel.configure()
        manager.createNotificationChannel(channel)
    }

    private fun v2(): NotificationChannel? = manager.getNotificationChannel(NotificationHelper.CHANNEL_ID_MESSAGES)
    private fun legacyChannel(): NotificationChannel? =
        manager.getNotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID_MESSAGES)

    // --- ids -----------------------------------------------------------------

    @Test fun `channel ids`() {
        assertEquals("messages_v2", NotificationHelper.CHANNEL_ID_MESSAGES)
        assertEquals("messages", NotificationHelper.LEGACY_CHANNEL_ID_MESSAGES)
        assertEquals("spam", NotificationHelper.CHANNEL_ID_SPAM)
        assertEquals("backfill", NotificationHelper.CHANNEL_ID_BACKFILL)
    }

    // --- messagesImportance ---------------------------------------------------

    @Test fun `no legacy channel gives high`() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, NotificationHelper.messagesImportance(null))
    }

    @Test fun `legacy default or high becomes high`() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            NotificationHelper.messagesImportance(NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            NotificationHelper.messagesImportance(NotificationManager.IMPORTANCE_HIGH))
    }

    @Test fun `legacy importance the user turned down is kept`() {
        for (importance in listOf(
            NotificationManager.IMPORTANCE_LOW,
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_NONE,
        )) {
            assertEquals("importance=$importance", importance, NotificationHelper.messagesImportance(importance))
        }
    }

    // --- createChannels -------------------------------------------------------

    @Test fun `fresh install creates messages_v2 at high and no legacy channel`() {
        NotificationHelper.createChannels(context)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, v2()!!.importance)
        assertNull(legacyChannel())
    }

    @Test fun `spam and backfill channels are low`() {
        NotificationHelper.createChannels(context)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_ID_SPAM)!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_ID_BACKFILL)!!.importance)
    }

    @Test fun `upgrade from legacy default gives high and deletes legacy`() {
        legacy(NotificationManager.IMPORTANCE_DEFAULT)
        NotificationHelper.createChannels(context)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, v2()!!.importance)
        assertNull(legacyChannel())
    }

    @Test fun `legacy turned down to low is kept on messages_v2`() {
        legacy(NotificationManager.IMPORTANCE_LOW)
        NotificationHelper.createChannels(context)
        assertEquals(NotificationManager.IMPORTANCE_LOW, v2()!!.importance)
        assertNull(legacyChannel())
    }

    @Test fun `legacy turned off is kept off on messages_v2`() {
        legacy(NotificationManager.IMPORTANCE_NONE)
        NotificationHelper.createChannels(context)
        assertEquals(NotificationManager.IMPORTANCE_NONE, v2()!!.importance)
    }

    @Test fun `vibration on and badge off are carried over`() {
        legacy(NotificationManager.IMPORTANCE_DEFAULT) {
            enableVibration(true)
            setShowBadge(false)
        }
        NotificationHelper.createChannels(context)
        val channel = v2()!!
        assertTrue(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
    }

    @Test fun `vibration off, silent sound and secret lockscreen are carried over`() {
        legacy(NotificationManager.IMPORTANCE_DEFAULT) {
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        NotificationHelper.createChannels(context)
        val channel = v2()!!
        assertFalse(channel.shouldVibrate())
        assertNull(channel.sound)
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
    }

    @Test fun `calling twice is safe and keeps messages_v2`() {
        legacy(NotificationManager.IMPORTANCE_LOW)
        NotificationHelper.createChannels(context)
        NotificationHelper.createChannels(context)
        val channel = v2()
        assertNotNull(channel)
        assertNull(legacyChannel())
        // The second call must not reset the carried-over importance.
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    // --- buildMessageNotification ---------------------------------------------

    private fun replyActions(n: Notification) =
        (n.actions ?: emptyArray()).filter { !it.remoteInputs.isNullOrEmpty() }

    @Test fun `phone number sender gets a reply action with remote input`() {
        NotificationHelper.createChannels(context)
        val n = NotificationHelper.buildMessageNotification(context, 5L, "+15551234567", "hello")
        assertEquals(1, replyActions(n).size)
        assertEquals(NotificationHelper.CHANNEL_ID_MESSAGES, n.channelId)
        assertEquals(Notification.CATEGORY_MESSAGE, n.category)
    }

    @Test fun `alphanumeric sender gets no reply action`() {
        NotificationHelper.createChannels(context)
        val n = NotificationHelper.buildMessageNotification(context, 5L, "MCI", "Your bill is ready")
        assertTrue(replyActions(n).isEmpty())
        assertEquals(Notification.CATEGORY_MESSAGE, n.category)
    }

    @Test fun `persian script sender gets no reply action`() {
        NotificationHelper.createChannels(context)
        val n = NotificationHelper.buildMessageNotification(context, 5L, "همراه اول", "سلام")
        assertTrue(replyActions(n).isEmpty())
    }

    @Test fun `numeric short code gets a reply action`() {
        NotificationHelper.createChannels(context)
        val n = NotificationHelper.buildMessageNotification(context, 5L, "3000123", "code 1234")
        assertEquals(1, replyActions(n).size)
    }

    @Test fun `spam notification posts on the spam channel`() {
        NotificationHelper.createChannels(context)
        val n = NotificationHelper.buildMessageNotification(context, 5L, "+15551234567", "win", isSpam = true)
        assertEquals(NotificationHelper.CHANNEL_ID_SPAM, n.channelId)
    }
}

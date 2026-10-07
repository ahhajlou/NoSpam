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

    // The conversation shortcut each notification publishes (long-press on the
    // app icon) opens the same conversation, so it needs the same fresh task.
    // Without it the launcher started it with NEW_TASK alone, Android handed it
    // to a restored MainActivity after process death, and the conversation on
    // screen before won: the emulator opened 5559998888 for 5559990001.
    // Google Messages' conversation shortcuts carry the same three flags.
    @Test fun `the conversation shortcut also opens in a fresh task`() {
        NotificationHelper.buildMessageNotification(context, threadId = 77L, sender = "+15557654321", messageBody = "Hi")
        val shortcut = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(context)
            .single { it.id == "thread-77" }
        val intent = shortcut.intent
        val required = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_TASK_ON_HOME
        assertEquals(required, intent.flags and required)
        assertEquals("com.nospam.nospam.MainActivity", intent.component?.className)
        assertEquals(77L, intent.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L))
    }

    // A shortcut published before the fix keeps its old intent until that
    // sender writes again, which may be never, so existing ones are repaired.
    @Test fun `shortcuts published without the flags are repaired, and nothing else changes`() {
        val stale = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, "thread-5")
            .setShortLabel("+15550000005")
            .setLongLabel("+15550000005")
            .setIntent(
                Intent(Intent.ACTION_VIEW, android.net.Uri.fromParts("sms", "+15550000005", null))
                    .setClassName(context.packageName, "com.nospam.nospam.MainActivity")
                    .putExtra(TelephonyConstants.EXTRA_THREAD_ID, 5L)
            )
            .build()
        androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(context, stale)

        NotificationHelper.repairConversationShortcuts(context)

        val repaired = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(context)
            .single { it.id == "thread-5" }
        val required = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_TASK_ON_HOME
        assertEquals(required, repaired.intent.flags and required)
        assertEquals(5L, repaired.intent.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L))
        assertEquals("com.nospam.nospam.MainActivity", repaired.intent.component?.className)
        assertEquals("+15550000005", repaired.shortLabel.toString())
    }

    @Test fun `repairing when nothing needs it is harmless`() {
        NotificationHelper.repairConversationShortcuts(context)
        NotificationHelper.buildMessageNotification(context, threadId = 9L, sender = "+15550000009", messageBody = "Hi")
        NotificationHelper.repairConversationShortcuts(context)
        val shortcut = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == "thread-9" }
        assertEquals(9L, shortcut.intent.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L))
    }
}

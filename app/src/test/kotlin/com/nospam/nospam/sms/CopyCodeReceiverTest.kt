// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.sms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A notification's "Copy 123456": the code goes to the clipboard, marked sensitive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyCodeReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    private fun copyIntent(code: String): Intent =
        shadowOf(MessageNotificationReceiver.copyCodeIntent(context, threadId = 5L, code = code)).savedIntent

    @Test fun `the code is copied and marked sensitive`() {
        MessageNotificationReceiver().onReceive(context, copyIntent("482913"))

        val clip = clipboard.primaryClip!!
        assertEquals("482913", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test fun `anything but a code is ignored`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("before", "before"))
        for (bad in listOf("", "12", "123456789", "https://example.com", "48 29 13")) {
            MessageNotificationReceiver().onReceive(context, copyIntent(bad))
        }
        MessageNotificationReceiver().onReceive(context, Intent(MessageNotificationReceiver.ACTION_COPY_CODE))

        assertEquals("before", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertFalse(clipboard.primaryClip!!.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") ?: false)
    }
}

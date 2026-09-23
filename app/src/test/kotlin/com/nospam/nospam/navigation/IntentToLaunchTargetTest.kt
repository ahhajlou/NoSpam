// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Written from the spec for [Intent.toLaunchTarget] independently of the
 * implementation, exercising the extraction over real [Intent] objects built
 * with Robolectric. The delimiter/precedence rules themselves are covered
 * exhaustively in [ParseLaunchIntentTest]; this file only checks that the
 * wiring from a real Intent (action, data, extras) is correct and that a
 * malformed intent never throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentToLaunchTargetTest {

    @Test fun `our own notification shape -- VIEW plus sms uri, thread id and EXTRA_TEXT -- yields Thread without the text as body`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromParts("sms", "+15551230000", null)).apply {
            putExtra("thread_id", 42L)
            putExtra(Intent.EXTRA_TEXT, "hello")
        }

        assertEquals(LaunchTarget.Thread(42L, "+15551230000"), intent.toLaunchTarget())
    }

    @Test fun `SENDTO smsto uri with sms_body extra yields Compose`() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+15551234")).apply {
            putExtra("sms_body", "hi")
        }

        assertEquals(LaunchTarget.Compose("+15551234", "hi"), intent.toLaunchTarget())
    }

    @Test fun `EXTRA_TEXT is used as the compose body when sms_body is absent`() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+15551234")).apply {
            putExtra(Intent.EXTRA_TEXT, "body from EXTRA_TEXT")
        }

        assertEquals(LaunchTarget.Compose("+15551234", "body from EXTRA_TEXT"), intent.toLaunchTarget())
    }

    @Test fun `intent with no action and no data returns null without throwing`() {
        val intent = Intent()

        assertNull(intent.toLaunchTarget())
    }

    @Test fun `unrelated action MAIN returns null`() {
        val intent = Intent(Intent.ACTION_MAIN)

        assertNull(intent.toLaunchTarget())
    }

    @Test fun `SENDTO with an unrelated scheme returns null`() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:foo@bar.com"))

        assertNull(intent.toLaunchTarget())
    }

    @Test fun `thread id extra absent defaults to -1 and is treated as absent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromParts("sms", "+15551234", null))

        assertEquals(LaunchTarget.Compose("+15551234", null), intent.toLaunchTarget())
    }
}

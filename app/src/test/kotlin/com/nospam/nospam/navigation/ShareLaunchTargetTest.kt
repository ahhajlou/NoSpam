// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SEND = "android.intent.action.SEND"
private const val SENDTO = "android.intent.action.SENDTO"

/**
 * Written from the spec for the SEND (share text in) rule of
 * [parseLaunchIntent], independently of the implementation.
 */
class ShareLaunchTargetTest {

    private fun send(
        text: String?,
        scheme: String? = null,
        ssp: String? = null,
        threadId: Long = -1L,
        smsBody: String? = null,
    ) = parseLaunchIntent(
        action = SEND, scheme = scheme, schemeSpecificPart = ssp,
        threadId = threadId, smsBody = smsBody, text = text
    )

    // --- Valid text --------------------------------------------------------

    @Test fun `SEND with plain text returns Share`() {
        assertEquals(LaunchTarget.Share("hello"), send("hello"))
    }

    @Test fun `SEND text is not trimmed`() {
        assertEquals(LaunchTarget.Share("  padded text \n"), send("  padded text \n"))
    }

    @Test fun `SEND preserves newlines, Persian and emoji exactly`() {
        val text = "سلام دنیا\nline two 👋🏽\r\nآخر"
        assertEquals(LaunchTarget.Share(text), send(text))
    }

    @Test fun `SEND with a single non-blank character returns Share`() {
        assertEquals(LaunchTarget.Share("x"), send("x"))
    }

    @Test fun `SEND with text of exactly MAX_SHARED_TEXT_LENGTH is accepted`() {
        val text = "a".repeat(MAX_SHARED_TEXT_LENGTH)
        assertEquals(LaunchTarget.Share(text), send(text))
    }

    @Test fun `SEND with text one over MAX_SHARED_TEXT_LENGTH returns null, not truncated`() {
        assertNull(send("a".repeat(MAX_SHARED_TEXT_LENGTH + 1)))
    }

    @Test fun `SEND with text far over the limit returns null`() {
        assertNull(send("b".repeat(MAX_SHARED_TEXT_LENGTH * 4)))
    }

    // --- Missing or blank text --------------------------------------------

    @Test fun `SEND with null text returns null`() {
        assertNull(send(null))
    }

    @Test fun `SEND with empty text returns null`() {
        assertNull(send(""))
    }

    @Test fun `SEND with whitespace-only text returns null`() {
        assertNull(send("   "))
    }

    @Test fun `SEND with newlines and tabs only returns null`() {
        assertNull(send("\n\t \r\n"))
    }

    // --- URI, threadId and smsBody are ignored for SEND --------------------

    @Test fun `SEND with an sms uri still yields Share`() {
        assertEquals(
            LaunchTarget.Share("shared"),
            send("shared", scheme = "sms", ssp = "+15551234")
        )
    }

    @Test fun `SEND with a positive thread id still yields Share`() {
        assertEquals(LaunchTarget.Share("shared"), send("shared", threadId = 42L))
    }

    @Test fun `SEND with sms uri, thread id and sms_body yields Share of the text`() {
        assertEquals(
            LaunchTarget.Share("shared"),
            send("shared", scheme = "smsto", ssp = "+15551234", threadId = 7L, smsBody = "body")
        )
    }

    @Test fun `SEND with sms_body but no text returns null`() {
        assertNull(send(null, smsBody = "body"))
    }

    @Test fun `SEND with sms_body, sms uri and thread id but no text returns null`() {
        assertNull(send(null, scheme = "sms", ssp = "+15551234", threadId = 9L, smsBody = "body"))
    }

    @Test fun `SEND with blank text and a valid sms uri returns null`() {
        assertNull(send("  ", scheme = "sms", ssp = "+15551234", smsBody = "body"))
    }

    @Test fun `SEND with over-long text and a valid sms uri returns null`() {
        assertNull(
            send("a".repeat(MAX_SHARED_TEXT_LENGTH + 1), scheme = "sms", ssp = "+15551234", threadId = 3L)
        )
    }

    // --- Sanity: existing rule unchanged -----------------------------------

    @Test fun `SENDTO with a recipient still yields Compose`() {
        assertEquals(
            LaunchTarget.Compose("+15551234", null),
            parseLaunchIntent(
                action = SENDTO, scheme = "sms", schemeSpecificPart = "+15551234",
                threadId = -1L, smsBody = null, text = null
            )
        )
    }
}

/**
 * Written from the spec for [Intent.toLaunchTarget]'s share handling,
 * independently of the implementation, over real [Intent] objects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentTest {

    private fun shareIntent(build: Intent.() -> Unit = {}) =
        Intent(Intent.ACTION_SEND).apply { type = "text/plain" }.apply(build)

    @Test fun `real share intent with EXTRA_TEXT yields Share`() {
        val intent = shareIntent { putExtra(Intent.EXTRA_TEXT, "look at this") }
        assertEquals(LaunchTarget.Share("look at this"), intent.toLaunchTarget())
    }

    @Test fun `share intent preserves Persian, emoji and newlines`() {
        val text = "سلام\nhttps://example.com 🎉"
        val intent = shareIntent { putExtra(Intent.EXTRA_TEXT, text) }
        assertEquals(LaunchTarget.Share(text), intent.toLaunchTarget())
    }

    @Test fun `EXTRA_TEXT as a styled SpannableString yields Share with its plain string`() {
        val styled = SpannableString("bold news").apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val intent = shareIntent { putExtra(Intent.EXTRA_TEXT, styled as CharSequence) }

        val result = intent.toLaunchTarget()

        assertEquals(LaunchTarget.Share("bold news"), result)
        // The text must be a plain String, not the spannable itself.
        assertEquals(String::class.java, (result as LaunchTarget.Share).text.javaClass)
    }

    @Test fun `share intent without a type still yields Share`() {
        val intent = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, "untyped") }
        assertEquals(LaunchTarget.Share("untyped"), intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with only EXTRA_STREAM yields null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
        }
        assertNull(intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with no extras yields null`() {
        assertNull(shareIntent().toLaunchTarget())
    }

    @Test fun `ACTION_SEND with blank EXTRA_TEXT yields null`() {
        assertNull(shareIntent { putExtra(Intent.EXTRA_TEXT, " \n ") }.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with over-long EXTRA_TEXT yields null`() {
        val intent = shareIntent { putExtra(Intent.EXTRA_TEXT, "z".repeat(MAX_SHARED_TEXT_LENGTH + 1)) }
        assertNull(intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with EXTRA_TEXT of exactly the limit yields Share`() {
        val text = "z".repeat(MAX_SHARED_TEXT_LENGTH)
        val intent = shareIntent { putExtra(Intent.EXTRA_TEXT, text) }
        assertEquals(LaunchTarget.Share(text), intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with only sms_body yields null`() {
        val intent = shareIntent { putExtra("sms_body", "body only") }
        assertNull(intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with sms uri, thread id and sms_body still yields Share of EXTRA_TEXT`() {
        val intent = Intent(Intent.ACTION_SEND, Uri.fromParts("sms", "+15551234", null)).apply {
            putExtra("thread_id", 42L)
            putExtra("sms_body", "body")
            putExtra(Intent.EXTRA_TEXT, "shared")
        }
        assertEquals(LaunchTarget.Share("shared"), intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with EXTRA_TEXT put as an Int yields null without throwing`() {
        val intent = shareIntent { putExtra(Intent.EXTRA_TEXT, 12345) }
        assertNull(intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with thread_id put as a String does not throw`() {
        val intent = shareIntent {
            putExtra("thread_id", "not a long")
            putExtra(Intent.EXTRA_TEXT, "shared")
        }
        assertEquals(LaunchTarget.Share("shared"), intent.toLaunchTarget())
    }

    @Test fun `ACTION_SEND with sms_body put as an Int and valid text yields Share`() {
        val intent = shareIntent {
            putExtra("sms_body", 7)
            putExtra(Intent.EXTRA_TEXT, "shared")
        }
        assertEquals(LaunchTarget.Share("shared"), intent.toLaunchTarget())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Written from the spec for [parseLaunchIntent] independently of the
 * implementation: the function is not read before or while writing these
 * cases. Covers the action gate, the threadId-vs-recipient branching, address
 * parsing (delimiters, trimming, length cap, case-insensitive scheme) and the
 * body-precedence rules.
 */
class ParseLaunchIntentTest {

    private val sixtyFour = "1".repeat(MAX_ADDRESS_LENGTH)
    private val sixtyFive = "1".repeat(MAX_ADDRESS_LENGTH + 1)

    // --- Rule 1: action gate -------------------------------------------------

    @Test fun `null action returns null even with a valid uri and thread id`() {
        assertNull(
            parseLaunchIntent(
                action = null, scheme = "sms", schemeSpecificPart = "+15551234",
                threadId = 5L, smsBody = null, text = null
            )
        )
    }

    @Test fun `MAIN action returns null`() {
        assertNull(
            parseLaunchIntent(
                action = "android.intent.action.MAIN", scheme = "sms", schemeSpecificPart = "+15551234",
                threadId = 5L, smsBody = null, text = null
            )
        )
    }

    @Test fun `SEND action returns null`() {
        assertNull(
            parseLaunchIntent(
                action = "android.intent.action.SEND", scheme = "sms", schemeSpecificPart = "+15551234",
                threadId = 0L, smsBody = "hi", text = null
            )
        )
    }

    @Test fun `SENDTO action with a valid sms uri returns Compose`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `VIEW action with a valid sms uri returns Compose`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.VIEW", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    // --- Rule 2: threadId branch ----------------------------------------------

    @Test fun `positive thread id returns Thread with address parsed from the uri`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.VIEW", scheme = "sms", schemeSpecificPart = "+15551230000",
            threadId = 42L, smsBody = null, text = "ignored"
        )
        assertEquals(LaunchTarget.Thread(42L, "+15551230000"), result)
    }

    @Test fun `positive thread id with no data returns Thread with null address`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.VIEW", scheme = null, schemeSpecificPart = null,
            threadId = 42L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Thread(42L, null), result)
    }

    @Test fun `positive thread id with an unrelated scheme returns Thread with null address, not overall null`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.VIEW", scheme = "tel", schemeSpecificPart = "+15551234",
            threadId = 42L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Thread(42L, null), result)
    }

    @Test fun `positive thread id with a blank scheme specific part returns Thread with null address`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.VIEW", scheme = "sms", schemeSpecificPart = "",
            threadId = 42L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Thread(42L, null), result)
    }

    @Test fun `body inputs are ignored for Thread -- our own notification shape`() {
        // ACTION_VIEW + sms:<sender> + thread_id + EXTRA_TEXT = the received
        // message's text; that text must NOT become a draft.
        val result = parseLaunchIntent(
            action = "android.intent.action.VIEW", scheme = "sms", schemeSpecificPart = "+15551230000",
            threadId = 42L, smsBody = "should not appear", text = "received message text"
        )
        assertEquals(LaunchTarget.Thread(42L, "+15551230000"), result)
    }

    @Test fun `thread id zero is treated as absent and falls through to recipient parsing`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = 0L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `thread id negative one is treated as absent`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `thread id negative other than -1 is treated as absent`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -99L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    // --- Rule 3: scheme + recipient parsing ------------------------------------

    @Test fun `recipients separated by comma -- first is the address`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234,+15555678",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `recipients separated by semicolon -- first is the address`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234;+15555678",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `recipients with mixed comma and semicolon delimiters`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+1555;+1666,+1777",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+1555", null), result)
    }

    @Test fun `leading empty recipients are skipped -- comma variant`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = ",,+1555",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+1555", null), result)
    }

    @Test fun `leading empty recipients are skipped -- whitespace and semicolon variant`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = " ; +1555",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+1555", null), result)
    }

    @Test fun `smsto with empty scheme specific part returns null`() {
        assertNull(
            parseLaunchIntent(
                action = "android.intent.action.SENDTO", scheme = "smsto", schemeSpecificPart = "",
                threadId = -1L, smsBody = null, text = null
            )
        )
    }

    @Test fun `recipient part empty but body present still returns null -- no usable address`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "?body=hi",
            threadId = -1L, smsBody = null, text = null
        )
        assertNull(result)
    }

    @Test fun `uppercase scheme SMSTO is accepted case-insensitively`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "SMSTO", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `mms and mmsto schemes are accepted`() {
        val mms = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "mms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        val mmsto = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "mmsto", schemeSpecificPart = "+15555678",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), mms)
        assertEquals(LaunchTarget.Compose("+15555678", null), mmsto)
    }

    @Test fun `other schemes -- tel, http, content, blank, null -- all return null`() {
        val schemes = listOf("tel", "http", "content", "", null)
        for (scheme in schemes) {
            val result = parseLaunchIntent(
                action = "android.intent.action.SENDTO", scheme = scheme, schemeSpecificPart = "+15551234",
                threadId = -1L, smsBody = null, text = null
            )
            assertNull("scheme=$scheme should yield null", result)
        }
    }

    @Test fun `address of exactly 64 characters is accepted`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = sixtyFour,
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose(sixtyFour, null), result)
    }

    @Test fun `address of 65 characters is rejected`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = sixtyFive,
            threadId = -1L, smsBody = null, text = null
        )
        assertNull(result)
    }

    @Test fun `alphanumeric sender id is a valid address, returned trimmed but otherwise unchanged`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = " MyBank ,+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("MyBank", null), result)
    }

    @Test fun `alphanumeric sender id with underscore is valid`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "NSTEST_FOO",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("NSTEST_FOO", null), result)
    }

    // --- Rule 4: body precedence -----------------------------------------------

    @Test fun `smsBody wins over text and a body query parameter`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234?body=fromuri",
            threadId = -1L, smsBody = "fromextra", text = "fromtext"
        )
        assertEquals(LaunchTarget.Compose("+15551234", "fromextra"), result)
    }

    @Test fun `text wins over the body query parameter when smsBody is absent`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234?body=fromuri",
            threadId = -1L, smsBody = null, text = "fromtext"
        )
        assertEquals(LaunchTarget.Compose("+15551234", "fromtext"), result)
    }

    @Test fun `body query parameter is used when smsBody and text are both absent`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234,+15555678?body=hi",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", "hi"), result)
    }

    @Test fun `body query parameter is found when it is not the first parameter`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms",
            schemeSpecificPart = "+15551234?subject=x&body=hello&other=y",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", "hello"), result)
    }

    @Test fun `no body anywhere yields null body`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `empty smsBody is reported as null body`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = "", text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `empty text is reported as null body when smsBody is absent`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234",
            threadId = -1L, smsBody = null, text = ""
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }

    @Test fun `empty body query parameter is reported as null body`() {
        val result = parseLaunchIntent(
            action = "android.intent.action.SENDTO", scheme = "sms", schemeSpecificPart = "+15551234?body=",
            threadId = -1L, smsBody = null, text = null
        )
        assertEquals(LaunchTarget.Compose("+15551234", null), result)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTest {

    /** Isolate marks are invisible; stripping them must give the value back. */
    private fun String.withoutIsolates() = filterNot { it in "\u2066\u2067\u2068\u2069\u200E\u200F" }

    @Test fun `a phone number is isolated so a right-to-left layout cannot reorder it`() {
        val wrapped = isolateLtr("+989121234567")
        assertTrue("expected isolation marks in $wrapped", wrapped != "+989121234567")
        assertEquals("+989121234567", wrapped.withoutIsolates())
    }

    @Test fun `the counter keeps its parts in order`() {
        assertEquals("12/2", isolateLtr("12/2").withoutIsolates())
    }

    @Test fun `an alphanumeric sender id survives unchanged`() {
        assertEquals("IRANCELL", isolateLtr("IRANCELL").withoutIsolates())
    }

    @Test fun `isolation does not depend on the default locale`() {
        val english = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            val inEnglish = isolateLtr("+98912")
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fa"))
            assertEquals(inEnglish, isolateLtr("+98912"))
        } finally {
            java.util.Locale.setDefault(english)
        }
    }

    @Test fun `an empty value gets no marks at all`() {
        assertEquals("", isolateLtr(""))
    }

    @Test fun `a phone number is isolated, an alphanumeric sender id is left alone`() {
        assertTrue(isolateIfPhoneNumber("+989121234567") != "+989121234567")
        assertEquals("IRANCELL", isolateIfPhoneNumber("IRANCELL"))
        // Ids carrying digits are still text, and other tests match them exactly.
        assertEquals("NSTEST_MUTE1", isolateIfPhoneNumber("NSTEST_MUTE1"))
    }

    @Test fun `written-out numbers count as phone numbers`() {
        listOf("+1 (555) 928-1102", "0912 123 4567", "5550102").forEach {
            assertEquals(it, isolateIfPhoneNumber(it).withoutIsolates())
            assertTrue("$it should be isolated", isolateIfPhoneNumber(it) != it)
        }
    }

    @Test fun `text without digits is never isolated`() {
        listOf("Alice", "Bank Alerts", "").forEach { assertEquals(it, isolateIfPhoneNumber(it)) }
    }
}

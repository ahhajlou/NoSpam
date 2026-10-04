// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Black-box contract for [PhoneNumberNormalizer.keyFor]: the sender key a raw
 * address gets when read in a region. Pure, so plain JUnit, no Robolectric.
 */
class SenderKeyTest {

    private val ir = "IR"

    /** raw -> expected key, all read in region IR. */
    private val cases: List<Pair<String, String>> = listOf(
        // Sender IDs: any letter, any script.
        "Snapp" to "SNAPP",
        "Bank Mellat" to "BANK MELLAT",
        "12345A" to "12345A",
        "  Snapp  " to "SNAPP",
        "همراه اول" to "همراه اول".uppercase(),
        // Empty stays empty.
        "" to "",
        "   " to "",
        // Service numbers that libphonenumber does not consider valid.
        "5000301630" to "+985000301630",
        "05000301630" to "+985000301630",
        "+985000301630" to "+985000301630",
        "00985000301630" to "+985000301630",
        "985000301630" to "+985000301630",
        "50002060004040" to "+9850002060004040",
        "+9850002060004040" to "+9850002060004040",
        "009850002060004040" to "+9850002060004040",
        "050002060004040" to "+9850002060004040",
        "300012345678" to "+98300012345678",
        "+98300012345678" to "+98300012345678",
        "0300012345678" to "+98300012345678",
        "98300012345678" to "+98300012345678",
        "30001234" to "+9830001234",
        "2000500666" to "+982000500666",
        "+982000500666" to "+982000500666",
        // Mobile numbers in every common shape.
        "09121234567" to "+989121234567",
        "+989121234567" to "+989121234567",
        "989121234567" to "+989121234567",
        "00989121234567" to "+989121234567",
        "9121234567" to "+989121234567",
        "+98 912 123 4567" to "+989121234567",
        "(0912) 123-4567" to "+989121234567",
        "۰۹۱۲۱۲۳۴۵۶۷" to "+989121234567",
        "  09121234567  " to "+989121234567",
        // Landline.
        "02188776655" to "+982188776655",
        "+982188776655" to "+982188776655",
        // Short codes.
        "1000" to "+981000",
        "8080" to "+988080",
        "+988080" to "+988080",
        "137" to "+98137",
        // Foreign with an international prefix.
        "+447700900123" to "+447700900123",
        "00447700900123" to "+447700900123",
        "+905321234567" to "+905321234567",
        // Documented limit: no "+" means Iranian.
        "447700900123" to "+98447700900123",
        // Unreadable: the trimmed text.
        "0" to "0",
        "+" to "+",
    )

    @Test
    fun `every case maps to its expected key in IR`() {
        val failures = cases.mapNotNull { (raw, expected) ->
            val actual = PhoneNumberNormalizer.keyFor(raw, ir)
            if (actual != expected) "keyFor(\"$raw\", IR) = \"$actual\", expected \"$expected\"" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `sender ids are upper-cased trimmed text`() {
        assertEquals("SNAPP", PhoneNumberNormalizer.keyFor("Snapp", ir))
        assertEquals("BANK MELLAT", PhoneNumberNormalizer.keyFor("Bank Mellat", ir))
        assertEquals("12345A", PhoneNumberNormalizer.keyFor("12345A", ir))
    }

    @Test
    fun `the bug case - with and without the country code are one sender`() {
        assertEquals(
            PhoneNumberNormalizer.keyFor("5000301630", ir),
            PhoneNumberNormalizer.keyFor("+985000301630", ir),
        )
        assertEquals("+985000301630", PhoneNumberNormalizer.keyFor("5000301630", ir))
    }

    @Test
    fun `different numbers of the same shape never collide`() {
        val a = PhoneNumberNormalizer.keyFor("+98100123451", ir)
        val b = PhoneNumberNormalizer.keyFor("98200012345", ir)
        assertNotEquals(a, b)
        // And each is distinct from its neighbour.
        assertNotEquals(
            PhoneNumberNormalizer.keyFor("+98100123451", ir),
            PhoneNumberNormalizer.keyFor("+98100123452", ir),
        )
        assertNotEquals(
            PhoneNumberNormalizer.keyFor("5000301630", ir),
            PhoneNumberNormalizer.keyFor("5000301631", ir),
        )
    }

    @Test
    fun `region is case-insensitive`() {
        val failures = cases.mapNotNull { (raw, _) ->
            val upper = PhoneNumberNormalizer.keyFor(raw, "IR")
            val lower = PhoneNumberNormalizer.keyFor(raw, "ir")
            if (upper != lower) "\"$raw\": IR=\"$upper\" ir=\"$lower\"" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `keyFor is idempotent`() {
        val failures = cases.mapNotNull { (raw, _) ->
            val once = PhoneNumberNormalizer.keyFor(raw, ir)
            val twice = PhoneNumberNormalizer.keyFor(once, ir)
            if (once != twice) "\"$raw\": once=\"$once\" twice=\"$twice\"" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `keyFor is pure - repeated calls agree`() {
        cases.forEach { (raw, _) ->
            assertEquals(PhoneNumberNormalizer.keyFor(raw, ir), PhoneNumberNormalizer.keyFor(raw, ir))
        }
    }

    @Test
    fun `key scheme names the library version`() {
        assertEquals("libphonenumber-9.0.40/1", PhoneNumberNormalizer.KEY_SCHEME)
    }
}

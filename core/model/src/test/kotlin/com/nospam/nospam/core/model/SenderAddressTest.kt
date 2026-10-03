// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written from the spec for [isAlphanumericSender], without reading its
 * implementation: an address is an alphanumeric sender ID when it holds at
 * least one letter in any script; anything made only of digits (Latin or
 * Persian/Arabic-Indic) and phone punctuation is a number.
 */
class SenderAddressTest {

    @Test fun `latin sender ids are alphanumeric`() {
        for (id in listOf("MCI", "Snapp", "Irancell", "mci", "A")) {
            assertTrue(id, isAlphanumericSender(id))
        }
    }

    @Test fun `persian script sender ids are alphanumeric`() {
        assertTrue(isAlphanumericSender("همراه اول"))
        assertTrue(isAlphanumericSender("ایرانسل"))
    }

    @Test fun `a sender id mixing letters and digits is alphanumeric`() {
        assertTrue(isAlphanumericSender("Bank123"))
        assertTrue(isAlphanumericSender("1000Snapp"))
    }

    @Test fun `international and national phone numbers are not alphanumeric`() {
        assertFalse(isAlphanumericSender("+989121234567"))
        assertFalse(isAlphanumericSender("09121234567"))
    }

    @Test fun `numeric short codes are not alphanumeric`() {
        assertFalse(isAlphanumericSender("3000123"))
        assertFalse(isAlphanumericSender("1000"))
    }

    @Test fun `formatted numbers with punctuation and spaces are not alphanumeric`() {
        assertFalse(isAlphanumericSender("+1 (555) 010-0000"))
    }

    @Test fun `persian and arabic-indic digit strings are not alphanumeric`() {
        assertFalse(isAlphanumericSender("۰۹۱۲۱۲۳۴۵۶۷")) // Extended Arabic-Indic (Persian)
        assertFalse(isAlphanumericSender("٠٩١٢١٢٣٤٥٦٧")) // Arabic-Indic
    }

    @Test fun `empty string is not alphanumeric`() {
        assertFalse(isAlphanumericSender(""))
    }
}

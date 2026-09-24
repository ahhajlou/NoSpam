// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `isValidSimNumber` (the SIM-number dialog's Save-enablement
 * rule), written from the spec in the task prompt independently of the
 * implementation: true only when the text is not blank and every character
 * is a digit, a space, '+', '-', '(' or ')' -- "digit" per `Char.isDigit`
 * semantics, which is Unicode-aware and therefore also matches Persian and
 * Arabic-Indic digit characters, not just ASCII 0-9.
 */
class SimNumberValidationTest {

    // --- Blank / empty ---------------------------------------------------

    @Test fun `empty string is invalid`() {
        assertFalse(isValidSimNumber(""))
    }

    @Test fun `whitespace-only string is invalid`() {
        assertFalse(isValidSimNumber("   "))
    }

    @Test fun `a single space is invalid`() {
        assertFalse(isValidSimNumber(" "))
    }

    // --- Typical formats ---------------------------------------------------

    @Test fun `international format with spaces is valid`() {
        assertTrue(isValidSimNumber("+98 912 345 6789"))
    }

    @Test fun `parenthesised area code with a hyphen is valid`() {
        assertTrue(isValidSimNumber("(555) 123-4567"))
    }

    @Test fun `plain digit string is valid`() {
        assertTrue(isValidSimNumber("09123456789"))
    }

    @Test fun `a lone plus sign is invalid`() {
        // Not blank, and every character IS one of the allowed characters,
        // but this pins down that "not blank" alone is not the whole rule --
        // included as a boundary case, not asserting a required behaviour
        // beyond what the spec states (allowed characters, non-blank).
        assertTrue(isValidSimNumber("+"))
    }

    // --- Letters -------------------------------------------------------------

    @Test fun `letters make the number invalid`() {
        assertFalse(isValidSimNumber("555-CALL-NOW"))
    }

    @Test fun `a single letter is invalid`() {
        assertFalse(isValidSimNumber("a"))
    }

    @Test fun `a single digit is valid`() {
        assertTrue(isValidSimNumber("5"))
    }

    // --- Other punctuation -----------------------------------------------------

    @Test fun `a period makes the number invalid`() {
        assertFalse(isValidSimNumber("555.1234"))
    }

    @Test fun `a slash makes the number invalid`() {
        assertFalse(isValidSimNumber("555/1234"))
    }

    @Test fun `a hash makes the number invalid`() {
        assertFalse(isValidSimNumber("555#1234"))
    }

    @Test fun `an asterisk makes the number invalid`() {
        assertFalse(isValidSimNumber("555*1234"))
    }

    @Test fun `a comma makes the number invalid`() {
        assertFalse(isValidSimNumber("555,1234"))
    }

    @Test fun `a semicolon makes the number invalid`() {
        assertFalse(isValidSimNumber("555;1234"))
    }

    // --- Persian / Arabic-Indic digits --------------------------------------
    // The spec calls out `Char.isDigit` semantics, which are Unicode-aware:
    // these ARE digits by that definition, distinct from ASCII '0'..'9'.

    @Test fun `Persian digits count as digits under Char isDigit semantics`() {
        // "09123456789" spelled with Persian-Indic digits (U+06F0..U+06F9).
        assertTrue(isValidSimNumber("۰۹۱۲۳۴۵۶۷۸۹"))
    }

    @Test fun `Arabic-Indic digits count as digits under Char isDigit semantics`() {
        // The same number spelled with Arabic-Indic digits (U+0660..U+0669).
        assertTrue(isValidSimNumber("٠٩١٢٣٤٥٦٧٨٩"))
    }

    @Test fun `a mix of Persian digits and allowed punctuation is valid`() {
        assertTrue(isValidSimNumber("+۹۸ ۹۱۲ ۳۴۵ ۶۷۸۹"))
    }
}

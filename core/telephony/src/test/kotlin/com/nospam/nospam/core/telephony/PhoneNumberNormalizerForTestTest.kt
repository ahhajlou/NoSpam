package com.nospam.nospam.core.telephony

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PhoneNumberNormalizer.normalizeForTest is a priority-5 target per the task
 * brief (core-telephony.md): it had zero dedicated tests. Unlike `normalize`,
 * this function reads `Locale.getDefault().country` fresh on every call (no
 * `@Volatile` object-level cache), so it's safe to vary the locale per test
 * method without cross-test contamination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneNumberNormalizerForTestTest {

    @Test fun `alphanumeric sender id is upper-cased, never phone-formatted`() {
        assertEquals("BANK MELLAT", PhoneNumberNormalizer.normalizeForTest("Bank Mellat"))
        assertEquals("SNAPP", PhoneNumberNormalizer.normalizeForTest("Snapp"))
    }

    @Test fun `case only differs converge to the same upper-cased form`() {
        assertEquals("SNAPP", PhoneNumberNormalizer.normalizeForTest("snapp"))
        assertEquals("SNAPP", PhoneNumberNormalizer.normalizeForTest("Snapp"))
        assertEquals("SNAPP", PhoneNumberNormalizer.normalizeForTest("SNAPP"))
    }

    @Test fun `a single trailing letter still takes the whole-value alphanumeric branch`() {
        // any{isLetter()} short-circuits the whole value to upper-case; it does
        // not selectively strip the letter or attempt partial E.164 formatting.
        assertEquals("12345A", PhoneNumberNormalizer.normalizeForTest("12345A"))
    }

    @Test fun `a local Iranian number normalizes to E164`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("fa", "IR"))
            assertEquals("+989121234567", PhoneNumberNormalizer.normalizeForTest("09121234567"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `an already E164 number normalizes to itself`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("fa", "IR"))
            assertEquals("+989121234567", PhoneNumberNormalizer.normalizeForTest("+989121234567"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `a malformed numeric string falls back to the trimmed raw value`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("fa", "IR"))
            assertEquals("12", PhoneNumberNormalizer.normalizeForTest("  12  "))
        } finally {
            Locale.setDefault(original)
        }
    }
}

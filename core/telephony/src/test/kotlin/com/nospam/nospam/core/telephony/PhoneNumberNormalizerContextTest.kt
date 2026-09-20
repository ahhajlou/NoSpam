// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PhoneNumberNormalizer.normalize/normalizedVariants/warm, the Context-based
 * (cached) entry points -- a priority-5 target per the task brief with no
 * prior dedicated test file. `countryIso` is resolved once and cached for the
 * life of the object (KDoc: "cannot meaningfully change while the process is
 * alive"), so every test in this class relies on the SAME resolved country --
 * set once, up front, via Locale (Robolectric's default TelephonyManager
 * shadow reports no network/SIM country, so resolution falls through to
 * Locale.getDefault()). This class runs in its own JVM fork (see
 * build.gradle.kts's `forkEvery = 1`) so no other test class's country
 * assumption can leak in first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneNumberNormalizerContextTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Locale.setDefault(Locale("fa", "IR"))
        // Establishes (or confirms) the cached country for this class's JVM.
        PhoneNumberNormalizer.warm(context)
    }

    @Test fun `alphanumeric sender id needs no country resolution`() {
        assertEquals("BANK MELLAT", PhoneNumberNormalizer.normalize(context, "Bank Mellat"))
    }

    @Test fun `a local Iranian number normalizes to E164`() {
        assertEquals("+989121234567", PhoneNumberNormalizer.normalize(context, "09121234567"))
    }

    @Test fun `repeated calls with the same raw address return an equal cached result`() {
        val first = PhoneNumberNormalizer.normalize(context, "09121234567")
        val second = PhoneNumberNormalizer.normalize(context, "09121234567")
        assertEquals(first, second)
    }

    @Test fun `normalizedVariants returns a single element when normalization is a no-op`() {
        // Alphanumeric input already trimmed+upper-cased round-trips to itself.
        assertEquals(listOf("SNAPP"), PhoneNumberNormalizer.normalizedVariants(context, "SNAPP"))
    }

    @Test fun `normalizedVariants returns three forms when normalization changes the value`() {
        val variants = PhoneNumberNormalizer.normalizedVariants(context, "09121234567")
        assertEquals(listOf("09121234567", "+989121234567", "+989121234567"), variants)
    }
}

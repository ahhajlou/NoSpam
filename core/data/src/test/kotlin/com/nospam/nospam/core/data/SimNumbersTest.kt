// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SettingsRepository's per-SIM entered phone numbers
 * (`simNumbers` / `setSimNumber`), written from the spec in the task prompt --
 * key format, trimming, removal and failure policy -- independently of the
 * implementation.
 */
class SimNumbersTest {

    @Test fun `simNumbers is empty by default`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.simNumbers.test {
            assertEquals(emptyMap<Int, String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSimNumber stores under the documented key in SIM_SETTINGS`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSimNumber(7, "+15551234567")
        assertEquals("+15551234567", fake.contents(PreferenceFile.SIM_SETTINGS)["sim_7_number"])
    }

    @Test fun `setSimNumber does not touch other preference files`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSimNumber(7, "+15551234567")
        assertTrue(fake.contents(PreferenceFile.SETTINGS).isEmpty())
        assertTrue(fake.contents(PreferenceFile.UI_SETTINGS).isEmpty())
        assertTrue(fake.contents(PreferenceFile.DRAFTS).isEmpty())
    }

    @Test fun `setSimNumber trims the stored number`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSimNumber(1, "  +1 555 000 1111  ")
        assertEquals("+1 555 000 1111", fake.contents(PreferenceFile.SIM_SETTINGS)["sim_1_number"])
    }

    @Test fun `simNumbers emits the stored value and follows changes`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.simNumbers.test {
            assertEquals(emptyMap<Int, String>(), awaitItem())
            repo.setSimNumber(1, "+15550001111")
            assertEquals(mapOf(1 to "+15550001111"), awaitItem())
            repo.setSimNumber(1, "+15559998888")
            assertEquals(mapOf(1 to "+15559998888"), awaitItem())
        }
    }

    @Test fun `numbers for different SIMs are independent`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSimNumber(1, "+15550001111")
        repo.setSimNumber(2, "+15559998888")
        repo.simNumbers.test {
            assertEquals(mapOf(1 to "+15550001111", 2 to "+15559998888"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSimNumber with null removes only that SIM's entry`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSimNumber(1, "+15550001111")
        repo.setSimNumber(2, "+15559998888")
        repo.setSimNumber(1, null)
        repo.simNumbers.test {
            assertEquals(mapOf(2 to "+15559998888"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSimNumber with empty string removes the entry`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSimNumber(1, "+15550001111")
        repo.setSimNumber(1, "")
        repo.simNumbers.test {
            assertEquals(emptyMap<Int, String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSimNumber with a blank string removes the entry`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSimNumber(1, "+15550001111")
        repo.setSimNumber(1, "   ")
        repo.simNumbers.test {
            assertEquals(emptyMap<Int, String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `removing a number that was never set is a no-op`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSimNumber(1, null)
        repo.simNumbers.test {
            assertEquals(emptyMap<Int, String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `simNumbers ignores keys that are not of the sim number form`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(
                PreferenceFile.SIM_SETTINGS to mapOf(
                    "sim_1_number" to "+15550001111",
                    "sim_number" to "+19998887777",       // no subscription id
                    "sim_abc_number" to "+19998887777",    // non-numeric id
                    "1_number" to "+19998887777",          // missing prefix
                    "sim_2_nickname" to "not a number key", // wrong suffix
                ),
            ),
        )
        val repo = SettingsRepository(fake)
        repo.simNumbers.test {
            assertEquals(mapOf(1 to "+15550001111"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `simNumbers ignores non-String values under a matching key`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(
                PreferenceFile.SIM_SETTINGS to mapOf(
                    "sim_1_number" to "+15550001111",
                    "sim_2_number" to 5551112222L,
                ),
            ),
        )
        val repo = SettingsRepository(fake)
        repo.simNumbers.test {
            assertEquals(mapOf(1 to "+15550001111"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `simNumbers emits an empty map on a read failure`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_1_number" to "+15550001111")),
        ).apply { failReads = true }
        val repo = SettingsRepository(fake)
        repo.simNumbers.test {
            assertEquals(emptyMap<Int, String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSimNumber on a failed write does not throw and stores nothing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = SettingsRepository(fake)
        repo.setSimNumber(1, "+15550001111")
        assertTrue(fake.contents(PreferenceFile.SIM_SETTINGS).isEmpty())
    }
}

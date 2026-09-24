// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsRepository's per-SIM delivery-report setting
 * (`deliveryReportSims` / `setDeliveryReports`): key format, default, removal,
 * independence per SIM, coexistence with SIM numbers, and failure policy.
 * Written from the spec independently of the implementation.
 */
class DeliveryReportSettingsTest {

    @Test fun `off by default for every SIM`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        assertEquals(emptySet<Int>(), repo.deliveryReportSims.first())
    }

    @Test fun `enabling stores true under the documented key in SIM_SETTINGS`() = runTest {
        val fake = FakePreferencesDataSource()
        SettingsRepository(fake).setDeliveryReports(3, true)
        assertEquals(true, fake.contents(PreferenceFile.SIM_SETTINGS)["sim_3_delivery_reports"])
    }

    @Test fun `enabling touches no other preference file`() = runTest {
        val fake = FakePreferencesDataSource()
        SettingsRepository(fake).setDeliveryReports(3, true)
        assertTrue(fake.contents(PreferenceFile.SETTINGS).isEmpty())
        assertTrue(fake.contents(PreferenceFile.UI_SETTINGS).isEmpty())
        assertTrue(fake.contents(PreferenceFile.DRAFTS).isEmpty())
    }

    @Test fun `disabling removes the key`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setDeliveryReports(3, true)
        repo.setDeliveryReports(3, false)
        assertFalse(fake.contents(PreferenceFile.SIM_SETTINGS).containsKey("sim_3_delivery_reports"))
        assertEquals(emptySet<Int>(), repo.deliveryReportSims.first())
    }

    @Test fun `disabling a SIM that was never enabled is a no-op`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setDeliveryReports(3, false)
        assertTrue(fake.contents(PreferenceFile.SIM_SETTINGS).isEmpty())
        assertEquals(emptySet<Int>(), repo.deliveryReportSims.first())
    }

    @Test fun `SIMs are independent`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setDeliveryReports(1, true)
        repo.setDeliveryReports(2, true)
        assertEquals(setOf(1, 2), repo.deliveryReportSims.first())
        repo.setDeliveryReports(1, false)
        assertEquals(setOf(2), repo.deliveryReportSims.first())
    }

    @Test fun `flow follows changes`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.deliveryReportSims.test {
            assertEquals(emptySet<Int>(), awaitItem())
            repo.setDeliveryReports(5, true)
            assertEquals(setOf(5), awaitItem())
            repo.setDeliveryReports(6, true)
            assertEquals(setOf(5, 6), awaitItem())
            repo.setDeliveryReports(5, false)
            assertEquals(setOf(6), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `reads a stored value`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_9_delivery_reports" to true)),
        )
        assertEquals(setOf(9), SettingsRepository(fake).deliveryReportSims.first())
    }

    @Test fun `non-true values and malformed keys are ignored`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(
                PreferenceFile.SIM_SETTINGS to mapOf(
                    "sim_1_delivery_reports" to true,
                    "sim_2_delivery_reports" to false,
                    "sim_3_delivery_reports" to "true",
                    "sim_4_delivery_reports" to 1,
                    "sim_abc_delivery_reports" to true,
                    "sim__delivery_reports" to true,
                    "sim_delivery_reports" to true,
                    "5_delivery_reports" to true,
                    "sim_6_delivery_report" to true,
                    "sim_7_number" to "+15550001111",
                ),
            ),
        )
        assertEquals(setOf(1), SettingsRepository(fake).deliveryReportSims.first())
    }

    @Test fun `a failed read is an empty set`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_1_delivery_reports" to true)),
        ).apply { failReads = true }
        SettingsRepository(fake).deliveryReportSims.test {
            assertEquals(emptySet<Int>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a failed write does not throw and stores nothing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        SettingsRepository(fake).setDeliveryReports(1, true)
        assertTrue(fake.contents(PreferenceFile.SIM_SETTINGS).isEmpty())
    }

    @Test fun `does not disturb SIM numbers in the same file`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSimNumber(1, "+15550001111")
        repo.setDeliveryReports(1, true)
        repo.setDeliveryReports(2, true)
        assertEquals(mapOf(1 to "+15550001111"), repo.simNumbers.first())
        repo.setDeliveryReports(1, false)
        assertEquals(mapOf(1 to "+15550001111"), repo.simNumbers.first())
        assertEquals("+15550001111", fake.contents(PreferenceFile.SIM_SETTINGS)["sim_1_number"])
        assertEquals(setOf(2), repo.deliveryReportSims.first())
    }

    @Test fun `SIM number changes do not disturb delivery reports`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setDeliveryReports(1, true)
        repo.setSimNumber(1, "+15550001111")
        repo.setSimNumber(1, null)
        assertEquals(setOf(1), repo.deliveryReportSims.first())
    }
}

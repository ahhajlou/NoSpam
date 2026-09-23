// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-SIM "Get SMS delivery reports" setting: SettingsViewModel's
 * `deliveryReportSims` state and `setDeliveryReports` (write-through with a
 * repository, in memory without), and the switch on the SIM page. Written
 * from the spec independently of the implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class DeliveryReportToggleTest {
    @get:Rule val rule = createComposeRule()

    private val label = "Get SMS delivery reports"

    private fun fakeWithSims(vararg ids: Int) = FakeTelephonyDataSource().apply {
        subscriptions = ids.map { TelephonyDataSource.SimInfo(subscriptionId = it, displayName = "SIM $it") }
    }

    // --- ViewModel ------------------------------------------------------------

    @Test fun `without a repository, setDeliveryReports changes state in memory per SIM`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val vm = SettingsViewModel()
            assertEquals(emptySet<Int>(), vm.uiState.value.deliveryReportSims)
            vm.setDeliveryReports(1, true)
            vm.setDeliveryReports(2, true)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf(1, 2), vm.uiState.value.deliveryReportSims)
            vm.setDeliveryReports(1, false)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf(2), vm.uiState.value.deliveryReportSims)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `with a repository, state reflects a stored value and writes go through`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val prefs = FakePreferencesDataSource(
                initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_4_delivery_reports" to true)),
            )
            val vm = SettingsViewModel(settings = SettingsRepository(prefs))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf(4), vm.uiState.value.deliveryReportSims)

            vm.setDeliveryReports(5, true)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf(4, 5), vm.uiState.value.deliveryReportSims)
            assertEquals(true, prefs.contents(PreferenceFile.SIM_SETTINGS)["sim_5_delivery_reports"])

            vm.setDeliveryReports(4, false)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(setOf(5), vm.uiState.value.deliveryReportSims)
            assertFalse(prefs.contents(PreferenceFile.SIM_SETTINGS).containsKey("sim_4_delivery_reports"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    // --- SIM page ---------------------------------------------------------------

    @Test fun `the switch is enabled and off by default`() {
        val vm = SettingsViewModel(fakeWithSims(3), settings = SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { SimSettingsScreen(subscriptionId = 3, viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText(label).apply {
            assertIsDisplayed()
            assertIsEnabled()
            assertIsOff()
        }
    }

    @Test fun `the switch reflects the stored value for this SIM`() {
        val prefs = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_3_delivery_reports" to true)),
        )
        val vm = SettingsViewModel(fakeWithSims(3, 4), settings = SettingsRepository(prefs))
        rule.setContent { SimSettingsScreen(subscriptionId = 3, viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText(label).apply {
            assertIsEnabled()
            assertIsOn()
        }
    }

    @Test fun `another SIM's stored value does not turn this SIM's switch on`() {
        val prefs = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_4_delivery_reports" to true)),
        )
        val vm = SettingsViewModel(fakeWithSims(3, 4), settings = SettingsRepository(prefs))
        rule.setContent { SimSettingsScreen(subscriptionId = 3, viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText(label).assertIsOff()
    }

    @Test fun `toggling stores the value for this SIM only, and toggling back removes it`() {
        val prefs = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SIM_SETTINGS to mapOf("sim_4_number" to "+15550001111")),
        )
        val vm = SettingsViewModel(fakeWithSims(3, 4), settings = SettingsRepository(prefs))
        rule.setContent { SimSettingsScreen(subscriptionId = 3, viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText(label).performClick()
        rule.waitUntil(5_000) { prefs.contents(PreferenceFile.SIM_SETTINGS)["sim_3_delivery_reports"] == true }
        rule.onNodeWithText(label).assertIsOn()
        assertEquals(
            mapOf("sim_3_delivery_reports" to true, "sim_4_number" to "+15550001111"),
            prefs.contents(PreferenceFile.SIM_SETTINGS),
        )

        rule.onNodeWithText(label).performClick()
        rule.waitUntil(5_000) { !prefs.contents(PreferenceFile.SIM_SETTINGS).containsKey("sim_3_delivery_reports") }
        rule.onNodeWithText(label).assertIsOff()
        assertEquals(mapOf("sim_4_number" to "+15550001111"), prefs.contents(PreferenceFile.SIM_SETTINGS))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

/**
 * Tests for the new appearance settings (theme, dynamic color) and the SIM
 * settings page written from the spec, not from the implementation. See
 * CLAUDE.md §9 for why Compose UI tests run through Robolectric and why the
 * phone-sized qualifiers are required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SettingsAppearanceAndSimRobolectricTest {
    @get:Rule val rule = createComposeRule()

    // --- General page: theme ------------------------------------------------

    @Test fun `the choose theme row is enabled and names the current theme`() {
        val vm = GeneralSettingsViewModel(SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(hasText("Choose theme") and hasText("System default")).apply {
            assertIsDisplayed()
            assertIsEnabled()
        }
    }

    @Test fun `choosing dark from the theme dialog stores it and updates the row`() {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = GeneralSettingsViewModel(repo)
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(hasText("Choose theme") and hasText("System default")).performClick()
        rule.waitForIdle()

        // Dialog options: "System default" (subtitle "Follow the device
        // setting"), "Light", "Dark".
        rule.onNode(hasText("System default") and hasText("Follow the device setting")).assertIsDisplayed()
        rule.onNodeWithText("Light").assertIsDisplayed()
        rule.onNodeWithText("Dark").assertIsDisplayed()

        rule.onNodeWithText("Dark").performClick()
        rule.waitUntil(5_000) { fake.contents(PreferenceFile.UI_SETTINGS)["theme"] == "DARK" }
        rule.waitForIdle()

        // Dialog closed.
        rule.onNode(hasText("System default") and hasText("Follow the device setting")).assertDoesNotExist()
        // Row now reads Dark.
        rule.onNode(hasText("Choose theme") and hasText("Dark")).assertIsDisplayed()
    }

    // --- General page: dynamic color ----------------------------------------

    @Test fun `use wallpaper colors switch reflects the stored value`() {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("dynamic_color" to true)),
        )
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText("Use wallpaper colors").apply {
            assertIsEnabled()
            assertIsOn()
        }
    }

    @Test fun `toggling use wallpaper colors stores the new value`() {
        val fake = FakePreferencesDataSource()
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText("Use wallpaper colors").assertIsOff()
        rule.onNodeWithText("Use wallpaper colors").performClick()
        rule.waitUntil(5_000) { fake.contents(PreferenceFile.UI_SETTINGS)["dynamic_color"] == true }
        rule.onNodeWithText("Use wallpaper colors").assertIsOn()
    }

    // --- SIM page -------------------------------------------------------------

    @Test fun `the SIM page fills in the display name and number once they arrive`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(
                TelephonyDataSource.SimInfo(subscriptionId = 7, displayName = "Personal SIM", number = "+15551234567"),
            )
        }
        val vm = SettingsViewModel(fake)
        rule.setContent { SimSettingsScreen(subscriptionId = 7, viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText("Personal SIM").assertIsDisplayed()
        rule.onNode(hasText("Phone number") and hasText("+15551234567", substring = true)).assertIsDisplayed()
    }

    @Test fun `the SIM page shows the fallback until the SIM list arrives, then fills in`() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(
                TelephonyDataSource.SimInfo(subscriptionId = 9, displayName = "Roaming SIM", number = "+15559876543"),
            )
            subscriptionsGate = gate
        }
        val vm = SettingsViewModel(fake)
        rule.setContent { SimSettingsScreen(subscriptionId = 9, viewModel = vm) }
        rule.waitForIdle()

        // Still on the generic fallback: the SIM's own data has not arrived yet.
        rule.onNodeWithText("Roaming SIM").assertDoesNotExist()

        gate.complete(Unit)
        rule.waitForIdle()

        rule.onNodeWithText("Roaming SIM").assertIsDisplayed()
        rule.onNode(hasText("Phone number") and hasText("+15559876543", substring = true)).assertIsDisplayed()
    }

    @Test fun `SIM page MMS-dependent rows are shown but disabled`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(
                TelephonyDataSource.SimInfo(subscriptionId = 3, displayName = "Work SIM", number = "+15557654321"),
            )
        }
        val vm = SettingsViewModel(fake)
        rule.setContent { SimSettingsScreen(subscriptionId = 3, viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(hasText("Group messaging") and hasText("Needs MMS support")).assertIsNotEnabled()
        rule.onNode(hasText("Auto-download MMS") and hasText("Needs MMS support")).assertIsNotEnabled()
        rule.onNode(hasText("Auto-download MMS when roaming") and hasText("Needs MMS support")).assertIsNotEnabled()
    }

    @Test fun `SIM page delivery reports row is shown but disabled`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(
                TelephonyDataSource.SimInfo(subscriptionId = 3, displayName = "Work SIM", number = "+15557654321"),
            )
        }
        val vm = SettingsViewModel(fake)
        rule.setContent { SimSettingsScreen(subscriptionId = 3, viewModel = vm) }
        rule.waitForIdle()

        rule.onNodeWithText("Get SMS delivery reports").apply {
            assertIsDisplayed()
            assertIsNotEnabled()
        }
    }

    // --- Removed rows -----------------------------------------------------

    @Test fun `spam protection page has no contacts bypass warning row`() {
        val vm = SpamSettingsViewModel(SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { SpamSettingsScreen(viewModel = vm) }
        rule.waitForIdle()

        rule.onAllNodesWithText("Warn about suspicious messages from contacts").assertCountEquals(0)
    }

    @Test fun `advanced page has no automatic spam deletion row`() {
        rule.setContent { AdvancedSettingsScreen() }
        rule.waitForIdle()

        rule.onAllNodesWithText("Delete old spam automatically").assertCountEquals(0)
    }
}

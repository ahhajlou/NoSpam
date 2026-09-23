// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the entered-phone-number display: the "Phone number" row's
 * supporting text on SimSettingsScreen (entered, else carrier, else
 * not-provided) and the same choice on the settings landing page. Written
 * from the spec in the task prompt independently of the implementation.
 * See CLAUDE.md §9 for why Compose UI tests run through Robolectric and why
 * the phone-sized qualifiers are required.
 *
 * The number-edit dialog itself (opening it, the Save/Clear/Cancel actions,
 * the text field) is deliberately NOT covered here: a text field inside a
 * dialog window never reaches "displayed" under Robolectric's Compose host
 * (confirmed independent of animation timing, of `createAndroidComposeRule`
 * vs `createComposeRule`, and of `@GraphicsMode(NATIVE)`), so that behaviour
 * is covered on a real device instead, by `.maestro/flows/sim_number.yaml`.
 * The dialog's Save-enablement rule is still covered here at the unit level
 * -- see SimNumberValidationTest for `isValidSimNumber`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SimNumberRobolectricTest {
    @get:Rule val rule = createComposeRule()

    private fun sim(id: Int, name: String, number: String?) =
        TelephonyDataSource.SimInfo(subscriptionId = id, displayName = name, number = number)

    @Test fun `the row shows the not-provided text when the SIM has no number`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(sim(5, "SIM Five", number = null))
        }
        val vm = SettingsViewModel(dataSource = fake, settings = SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { SimSettingsScreen(subscriptionId = 5, viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(hasText("Phone number") and hasText("Not provided by this SIM. Tap to enter it")).assertIsDisplayed()
    }

    @Test fun `the row shows the carrier number when nothing was entered`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(sim(6, "SIM Six", number = "+15551112222"))
        }
        val vm = SettingsViewModel(dataSource = fake, settings = SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { SimSettingsScreen(subscriptionId = 6, viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(hasText("Phone number") and hasText("+15551112222", substring = true)).assertIsDisplayed()
        rule.onAllNodesWithText("entered by you", substring = true).assertCountEquals(0)
    }

    @Test fun `the row shows the entered number, not the carrier number, when both exist`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(sim(7, "SIM Seven", number = "+15550001111"))
        }
        val repo = SettingsRepository(FakePreferencesDataSource())
        runBlocking { repo.setSimNumber(7, "+15559998888") }
        val vm = SettingsViewModel(dataSource = fake, settings = repo)
        rule.setContent { SimSettingsScreen(subscriptionId = 7, viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(
            hasText("Phone number") and
                hasText("+15559998888", substring = true) and
                hasText("entered by you", substring = true),
        ).assertIsDisplayed()
        rule.onAllNodesWithText("+15550001111", substring = true).assertCountEquals(0)
    }

    // --- The landing page ----------------------------------------------------

    @Test fun `the landing page shows each SIM's number using the same entered-else-carrier-else-unknown choice`() {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(
                sim(31, "Entered SIM", number = "+15550001111"),
                sim(32, "Carrier SIM", number = "+15552223333"),
                sim(33, "Unknown SIM", number = null),
            )
        }
        val repo = SettingsRepository(FakePreferencesDataSource())
        runBlocking { repo.setSimNumber(31, "+15559998888") }
        val vm = SettingsViewModel(dataSource = fake, settings = repo)
        rule.setContent { SettingsScreen(title = "Settings", viewModel = vm) }
        rule.waitForIdle()

        rule.onNode(hasText("Entered SIM") and hasText("+15559998888", substring = true)).assertIsDisplayed()
        rule.onAllNodesWithText("+15550001111", substring = true).assertCountEquals(0)
        rule.onNode(hasText("Carrier SIM") and hasText("+15552223333", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("Unknown SIM") and hasText("Not provided by this SIM")).assertIsDisplayed()
    }
}

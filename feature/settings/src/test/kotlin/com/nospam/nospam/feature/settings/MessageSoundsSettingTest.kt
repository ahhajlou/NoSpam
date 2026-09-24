// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for in-app message sounds in feature:settings --
 * GeneralSettingsViewModel's `messageSounds` state and `setMessageSounds`
 * (write-through with a repository, in memory without), and the "Hear
 * outgoing and incoming message sounds" switch on the General page, which
 * must be enabled (it used to be disabled). Written from the task spec
 * independently of GeneralSettingsViewModel.kt and SettingsPages.kt.
 *
 * See CLAUDE.md §9 for why Compose UI tests run through Robolectric and why
 * the phone-sized qualifiers are required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MessageSoundsSettingTest {
    @get:Rule val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val label = "Hear outgoing and incoming message sounds"

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /**
     * Our own [dispatcher] is now installed as Dispatchers.Main, shadowing the
     * Compose test rule's own Main dispatcher -- so rule.waitUntil (which only
     * drains the rule's internal clock) can never observe a write the
     * ViewModel launches on Main. Pump both explicitly instead.
     */
    private fun settleUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            rule.waitForIdle()
            Thread.sleep(5)
        }
        dispatcher.scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    // --- ViewModel -------------------------------------------------------

    @Test fun `with no repository the state starts true`() = runTest {
        val vm = GeneralSettingsViewModel()
        assertTrue(vm.uiState.value.messageSounds)
    }

    @Test fun `with no repository setMessageSounds changes state in memory`() = runTest {
        val vm = GeneralSettingsViewModel()
        vm.setMessageSounds(false)
        assertEquals(false, vm.uiState.value.messageSounds)
    }

    @Test fun `with a repository the state reflects a pre-stored value`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("message_sounds" to false)),
        )
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.messageSounds)
    }

    @Test fun `setMessageSounds writes through to storage and the state follows it`() = runTest {
        val fake = FakePreferencesDataSource()
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        dispatcher.scheduler.advanceUntilIdle()

        vm.setMessageSounds(false)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.messageSounds)
        assertEquals(false, fake.contents(PreferenceFile.SETTINGS)["message_sounds"])
    }

    // --- General page: message sounds switch ------------------------------

    @Test fun `the switch is enabled and on by default`() {
        val vm = GeneralSettingsViewModel(SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.onNodeWithText(label).performScrollTo()
        rule.waitForIdle()

        rule.onNodeWithText(label).apply {
            assertIsDisplayed()
            assertIsEnabled()
            assertIsOn()
        }
    }

    @Test fun `the switch reflects a stored false value`() {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("message_sounds" to false)),
        )
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        settleUntil { vm.uiState.value.messageSounds == false }
        rule.onNodeWithText(label).performScrollTo()
        rule.waitForIdle()

        rule.onNodeWithText(label).apply {
            assertIsEnabled()
            assertIsOff()
        }
    }

    @Test fun `toggling the switch stores the new value`() {
        val fake = FakePreferencesDataSource()
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.onNodeWithText(label).performScrollTo()
        rule.waitForIdle()

        rule.onNodeWithText(label).assertIsOn()
        rule.onNodeWithText(label).performClick()
        settleUntil { fake.contents(PreferenceFile.SETTINGS)["message_sounds"] == false }

        rule.onNodeWithText(label).assertIsOff()
    }

    @Test fun `toggling the switch back on stores true again`() {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("message_sounds" to false)),
        )
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        settleUntil { vm.uiState.value.messageSounds == false }
        rule.onNodeWithText(label).performScrollTo()
        rule.waitForIdle()

        rule.onNodeWithText(label).assertIsOff()
        rule.onNodeWithText(label).performClick()
        settleUntil { fake.contents(PreferenceFile.SETTINGS)["message_sounds"] == true }

        rule.onNodeWithText(label).assertIsOn()
    }
}

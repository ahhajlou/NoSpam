// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.model.SwipeAction
import com.nospam.nospam.core.model.SwipeActions
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for configurable swipe actions in feature:settings --
 * GeneralSettingsViewModel's swipeActions state, and the General page's
 * "Swipe actions" section (rows "Right swipe" / "Left swipe", a dialog
 * titled like the row listing the four SwipeAction options) -- written from
 * the spec, independently of GeneralSettingsViewModel.kt and
 * SettingsPages.kt.
 *
 * See CLAUDE.md §9 for why Compose UI tests run through Robolectric and why
 * the phone-sized qualifiers are required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SwipeSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()

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

    @Test fun `with no repository the state starts at archive-archive defaults`() = runTest {
        val vm = GeneralSettingsViewModel()
        assertEquals(SwipeActions(), vm.uiState.value.swipeActions)
    }

    @Test fun `with no repository setSwipeActions changes state in memory`() = runTest {
        val vm = GeneralSettingsViewModel()
        val actions = SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.TOGGLE_READ)
        vm.setSwipeActions(actions)
        assertEquals(actions, vm.uiState.value.swipeActions)
    }

    @Test fun `with a repository the state reflects a pre-stored value`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("swipe_right" to "DELETE", "swipe_left" to "NONE")),
        )
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.NONE), vm.uiState.value.swipeActions)
    }

    @Test fun `setSwipeActions writes through to storage and the state follows it`() = runTest {
        val fake = FakePreferencesDataSource()
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        dispatcher.scheduler.advanceUntilIdle()

        val actions = SwipeActions(right = SwipeAction.TOGGLE_READ, left = SwipeAction.DELETE)
        vm.setSwipeActions(actions)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(actions, vm.uiState.value.swipeActions)
        assertEquals("TOGGLE_READ", fake.contents(PreferenceFile.SETTINGS)["swipe_right"])
        assertEquals("DELETE", fake.contents(PreferenceFile.SETTINGS)["swipe_left"])
    }

    // --- General page: "Swipe actions" section ----------------------------

    @Test fun `the section lists right and left swipe rows naming the current action`() {
        val vm = GeneralSettingsViewModel(SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.onNodeWithText("Swipe actions").performScrollTo()
        rule.waitForIdle()

        rule.onNode(hasText("Right swipe") and hasText("Archive")).assertIsDisplayed()
        rule.onNode(hasText("Left swipe") and hasText("Archive")).assertIsDisplayed()
    }

    @Test fun `tapping the right swipe row opens a dialog titled like the row listing the four options`() {
        val vm = GeneralSettingsViewModel(SettingsRepository(FakePreferencesDataSource()))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.onNodeWithText("Swipe actions").performScrollTo()
        rule.waitForIdle()

        rule.onNode(hasText("Right swipe") and hasText("Archive")).performClick()
        rule.waitForIdle()

        // The row's own title plus the dialog's title, both "Right swipe".
        rule.onAllNodesWithText("Right swipe").assertCountEquals(2)
        rule.onNodeWithText("None").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Mark as read or unread").assertIsDisplayed()
    }

    @Test fun `choosing delete for the right swipe stores it for that side only and updates the row`() {
        val fake = FakePreferencesDataSource()
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.onNodeWithText("Swipe actions").performScrollTo()
        rule.waitForIdle()

        rule.onNode(hasText("Right swipe") and hasText("Archive")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        settleUntil { fake.contents(PreferenceFile.SETTINGS)["swipe_right"] == "DELETE" }

        rule.onNode(hasText("Right swipe") and hasText("Delete")).assertIsDisplayed()
        rule.onNode(hasText("Left swipe") and hasText("Archive")).assertIsDisplayed()
    }

    @Test fun `choosing mark as read or unread for the left swipe stores it for that side only and updates the row`() {
        val fake = FakePreferencesDataSource()
        val vm = GeneralSettingsViewModel(SettingsRepository(fake))
        rule.setContent { GeneralSettingsScreen(viewModel = vm) }
        rule.onNodeWithText("Swipe actions").performScrollTo()
        rule.waitForIdle()

        rule.onNode(hasText("Left swipe") and hasText("Archive")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Mark as read or unread").performClick()
        settleUntil { fake.contents(PreferenceFile.SETTINGS)["swipe_left"] == "TOGGLE_READ" }

        rule.onNode(hasText("Left swipe") and hasText("Mark as read or unread")).assertIsDisplayed()
        rule.onNode(hasText("Right swipe") and hasText("Archive")).assertIsDisplayed()
    }
}

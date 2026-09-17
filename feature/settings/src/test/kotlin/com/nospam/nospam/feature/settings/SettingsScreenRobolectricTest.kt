package com.nospam.nospam.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings on the JVM; see CLAUDE.md §9 for why Compose UI tests run through
 * Robolectric and why the phone-sized qualifiers are required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SettingsScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    private fun toggleables() =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    @Test fun `the landing page lists every section`() {
        rule.setContent { SettingsScreen(title = "Settings") }
        rule.onNodeWithText("General").assertIsDisplayed()
        rule.onNodeWithText("Spam protection").assertIsDisplayed()
        rule.onNodeWithText("Advanced").assertIsDisplayed()
        rule.onNodeWithText("About").assertIsDisplayed()
    }

    @Test fun `each section reports which page to open`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            SettingsScreen(
                title = "Settings",
                onOpenGeneral = { opened.add("general") },
                onOpenSpamProtection = { opened.add("spam") },
                onOpenAdvanced = { opened.add("advanced") },
                onOpenAbout = { opened.add("about") },
            )
        }
        rule.onNodeWithText("General").performClick()
        rule.onNodeWithText("Spam protection").performClick()
        rule.onNodeWithText("Advanced").performClick()
        rule.onNodeWithText("About").performClick()
        assertEquals(listOf("general", "spam", "advanced", "about"), opened)
    }

    @Test fun `the language picker opens from the general page`() {
        rule.setContent { GeneralSettingsScreen() }
        rule.onNodeWithText("Language").performClick()
        rule.waitForIdle()
        // "System default" also labels the theme row and the language row, so
        // the dialog is identified by its title.
        rule.onNodeWithText("App language").assertIsDisplayed()
        rule.onNodeWithText("فارسی").assertIsDisplayed()
    }

    @Test fun `settings without storage yet are shown disabled, not silently inert`() {
        rule.setContent { GeneralSettingsScreen() }
        rule.onNodeWithText("Choose theme").assertIsNotEnabled()
        rule.onNodeWithText("Use wallpaper colors").assertIsNotEnabled()
    }

    @Test fun `spam protection toggles off and back on`() {
        rule.setContent { SpamSettingsScreen() }
        toggleables()[0].assertIsOn()
        toggleables()[0].performClick()
        // The switch follows DataStore, which writes on its own dispatcher.
        rule.waitUntil(5_000) { toggleables()[0].fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off }
        toggleables()[0].performClick()
        rule.waitUntil(5_000) { toggleables()[0].fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On }
    }

    @Test fun `re-check reports to its caller and confirms in a snackbar`() {
        var rechecked = 0
        rule.setContent { AdvancedSettingsScreen(onRecheck = { rechecked++ }) }
        rule.onNodeWithText("Re-check all messages").performClick()
        rule.waitForIdle()
        assertEquals(1, rechecked)
        rule.onNodeWithText("Re-check started in the background").assertIsDisplayed()
    }

    @Test fun `about shows the version and opens the terms`() {
        rule.setContent { AboutSettingsScreen() }
        rule.onNodeWithText("Version info").assertIsDisplayed()
        rule.onNodeWithText("Terms of service").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Close").assertIsDisplayed()
    }
}

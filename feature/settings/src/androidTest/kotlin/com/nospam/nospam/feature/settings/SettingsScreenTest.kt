package com.nospam.nospam.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun toggleables() =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    @Test fun `sections_and_rows_are_shown`() {
        rule.setContent { SettingsScreen(title = "Settings") }
        rule.onNodeWithText("General").assertIsDisplayed()
        rule.onNodeWithText("Privacy & protection").assertIsDisplayed()
        rule.onNodeWithText("Spam protection").assertIsDisplayed()
        rule.onNodeWithText("Version info").assertIsDisplayed()
    }

    @Test fun `spam_protection_switch_toggles_off_and_on`() {
        rule.setContent { SettingsScreen(title = "Settings") }
        // First switch in layout order is Spam protection (starts on).
        toggleables()[0].assertIsOn()
        toggleables()[0].performClick()
        rule.waitForIdle()
        toggleables()[0].assertIsOff()
    }

    @Test fun `language_row_opens_picker_dialog`() {
        rule.setContent { SettingsScreen(title = "Settings") }
        rule.onNodeWithText("Language").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("System default").assertIsDisplayed()
        rule.onNodeWithText("فارسی").assertIsDisplayed()
    }
}

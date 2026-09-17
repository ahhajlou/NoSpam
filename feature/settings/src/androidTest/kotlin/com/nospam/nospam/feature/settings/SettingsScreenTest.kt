package com.nospam.nospam.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `top_level_lists_every_section`() {
        rule.setContent { SettingsScreen(title = "Settings") }
        rule.onNodeWithText("General").assertIsDisplayed()
        rule.onNodeWithText("Spam protection").assertIsDisplayed()
        rule.onNodeWithText("Advanced").assertIsDisplayed()
        rule.onNodeWithText("About").assertIsDisplayed()
    }

    @Test fun `each_section_opens_its_own_page`() {
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

    @Test fun `language_row_opens_picker_dialog`() {
        rule.setContent { GeneralSettingsScreen() }
        rule.onNodeWithText("Language").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("System default").assertIsDisplayed()
        rule.onNodeWithText("فارسی").assertIsDisplayed()
    }
}

package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpamScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `spam_rows_are_shown`() {
        rule.setContent { SpamScreen(title = "Spam & blocked") }
        rule.onNodeWithText("Win A Free Cruise!").assertIsDisplayed()
        rule.onNodeWithText("Blocked").assertIsDisplayed()
    }

    @Test fun `not_spam_from_selection_removes_row_and_reports_id`() {
        val reported = mutableListOf<Pair<Long, String>>()
        rule.setContent { SpamScreen(title = "Spam & blocked", onNotSpam = { id, addr -> reported.add(id to addr) }) }
        rule.onNodeWithText("Win A Free Cruise!").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Not spam").performClick()
        rule.waitForIdle()
        assertEquals(listOf(201L to "Win A Free Cruise!"), reported)
        rule.onNodeWithText("Win A Free Cruise!").assertDoesNotExist()
    }

    @Test fun `block_asks_for_confirmation_first`() {
        val blocked = mutableListOf<String>()
        rule.setContent { SpamScreen(title = "Spam & blocked", onBlock = { blocked.add(it) }) }
        rule.onNodeWithText("Win A Free Cruise!").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Block").performClick()
        rule.waitForIdle()
        assertTrue(blocked.isEmpty())
        rule.onNodeWithText("Block 1 sender?").assertIsDisplayed()
    }
}

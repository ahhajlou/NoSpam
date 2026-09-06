package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpamScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `spam_banner_and_rows_are_shown`() {
        rule.setContent { SpamScreen() }
        rule.onNodeWithText("Spam messages will be deleted automatically after 30 days.").assertIsDisplayed()
        rule.onNodeWithText("Win A Free Cruise!").assertIsDisplayed()
        rule.onNodeWithText("Empty Spam").assertIsDisplayed()
    }

    @Test fun `not_spam_removes_row_and_reports_id`() {
        val reported = mutableListOf<Long>()
        rule.setContent { SpamScreen(onNotSpam = { reported.add(it) }) }
        assertEquals(3, rule.onAllNodesWithText("Not spam").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Not spam")[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf(201L), reported)
        assertEquals(2, rule.onAllNodesWithText("Not spam").fetchSemanticsNodes().size)
    }

    @Test fun `empty_spam_clears_list`() {
        rule.setContent { SpamScreen() }
        rule.onNodeWithText("Empty Spam").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Not spam").assertCountEquals(0)
    }
}

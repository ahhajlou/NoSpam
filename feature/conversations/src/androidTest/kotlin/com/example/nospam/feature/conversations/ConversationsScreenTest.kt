package com.example.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `inbox_shows_pinned_and_recent_rows_plus_fab`() {
        var newMessageClicked = false
        rule.setContent {
            ConversationsScreen(
                viewModel = ConversationsViewModel(),
                onNewMessage = { newMessageClicked = true }
            )
        }
        rule.onNodeWithText("Alice Smith").assertIsDisplayed()
        rule.onNodeWithText("Pinned").assertIsDisplayed()
        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onNodeWithText("Start chat").assertIsDisplayed().performClick()
        assertTrue(newMessageClicked)
    }

    @Test fun `filter_chip_selection_changes`() {
        rule.setContent { ConversationsScreen(viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Starred").assertIsDisplayed().performClick()
        rule.onNodeWithText("Starred").assertIsSelected()
    }

    @Test fun `search_field_accepts_input`() {
        rule.setContent { ConversationsScreen(viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Search conversations").assertIsDisplayed().performClick()
        rule.onNodeWithText("Search conversations").performTextInput("alice")
        rule.onNodeWithText("alice", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `conversation_click_reports_thread_id`() {
        var clicked: Long? = null
        rule.setContent {
            ConversationsScreen(
                viewModel = ConversationsViewModel(),
                onConversationClick = { clicked = it }
            )
        }
        rule.onNodeWithText("Design Team Sync").performClick()
        assertEquals(2L, clicked)
    }

    @Test fun `long_press_opens_inbox_actions`() {
        rule.setContent { ConversationsScreen(viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.waitForIdle()
        // Thread 2 is read in the fake seed, so the toggle reads "unread".
        rule.onNodeWithText("Mark as unread").assertIsDisplayed()
        rule.onNodeWithText("Archive").assertIsDisplayed()
        rule.onNodeWithText("Report spam").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test fun `long_press_archive_action_reports_id`() {
        var archived: Long? = null
        rule.setContent {
            ConversationsScreen(
                viewModel = ConversationsViewModel(),
                onArchive = { archived = it }
            )
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Archive").performClick()
        rule.waitForIdle()
        assertEquals(2L, archived)
    }
}

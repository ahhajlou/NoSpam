package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `inbox_shows_pinned_and_recent_rows_plus_fab`() {
        var newMessageClicked = false
        rule.setContent {
            ConversationsScreen(
                title = "Inbox",
                viewModel = ConversationsViewModel(),
                onNewMessage = { newMessageClicked = true }
            )
        }
        rule.onNodeWithText("Inbox").assertIsDisplayed()
        rule.onNodeWithText("Alice Smith").assertIsDisplayed()
        rule.onNodeWithText("Pinned").assertIsDisplayed()
        rule.onNodeWithText("Recent").assertIsDisplayed()
        // Icon-only FAB: its label is the content description.
        rule.onNodeWithContentDescription("Start chat").assertIsDisplayed().performClick()
        assertTrue(newMessageClicked)
    }

    @Test fun `filter_chip_selection_changes`() {
        rule.setContent { ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Starred").assertIsDisplayed().performClick()
        rule.onNodeWithText("Starred").assertIsSelected()
    }

    @Test fun `search_field_accepts_input`() {
        rule.setContent { ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Search conversations").assertIsDisplayed().performClick()
        rule.onNodeWithText("Search conversations").performTextInput("alice")
        rule.onNodeWithText("alice", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `conversation_click_reports_thread_id`() {
        var clicked: Long? = null
        rule.setContent {
            ConversationsScreen(
                title = "Inbox",
                viewModel = ConversationsViewModel(),
                onConversationClick = { clicked = it }
            )
        }
        rule.onNodeWithText("Design Team Sync").performClick()
        assertEquals(2L, clicked)
    }

    @Test fun `long_press_enters_selection_with_actions_in_the_top_bar`() {
        rule.setContent { ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pin").assertIsDisplayed()
        rule.onNodeWithContentDescription("Archive").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").assertIsDisplayed()
        rule.onNodeWithContentDescription("More options").performClick()
        // Thread 2 is read in the fake seed, so the read action reads "unread".
        rule.onNodeWithText("Mark as unread").assertIsDisplayed()
        rule.onNodeWithText("Report spam").assertIsDisplayed()
    }

    @Test fun `tap_while_selecting_adds_to_the_selection_instead_of_opening`() {
        var clicked: Long? = null
        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onConversationClick = { clicked = it })
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.onNodeWithText("Delivery Driver").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        assertNull(clicked)
    }

    @Test fun `archive_action_reports_id_and_ends_selection`() {
        var archived: Long? = null
        rule.setContent {
            ConversationsScreen(
                title = "Inbox",
                viewModel = ConversationsViewModel(),
                onArchive = { archived = it }
            )
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Archive").performClick()
        rule.waitForIdle()
        assertEquals(2L, archived)
        rule.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test fun `delete_asks_for_confirmation_first`() {
        var deleted: Long? = null
        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onDelete = { deleted = it })
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        assertNull(deleted)
        rule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(2L, deleted)
    }
}

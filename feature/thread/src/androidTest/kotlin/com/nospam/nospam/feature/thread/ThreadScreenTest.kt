package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThreadScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `title_shows_the_contact_name_and_conversation_actions`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithContentDescription("Call").assertIsDisplayed()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Archive").assertIsDisplayed()
        rule.onNodeWithText("Delete conversation").assertIsDisplayed()
    }

    @Test fun `tapping_a_message_reveals_its_time`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performClick()
        rule.waitForIdle()
        // The bubble is unchanged; a timestamp row appears beneath it.
        assertEquals(1, rule.onAllNodesWithText("Meet you there at 12:30?").fetchSemanticsNodes().size)
    }

    @Test fun `send_is_disabled_until_the_draft_has_text`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onNodeWithText("SMS message").performTextInput("hi")
        rule.onNodeWithContentDescription("Send message").assertIsEnabled()
    }

    @Test fun `thread_shows_fake_messages_and_sends_draft`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").assertIsDisplayed()
        rule.onNodeWithText("SMS message").performTextInput("See you!")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("See you!").assertIsDisplayed()
    }

    @Test fun `long_press_message_enters_selection_with_actions_in_the_top_bar`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        rule.onNodeWithContentDescription("Forward").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").assertIsDisplayed()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Share").assertIsDisplayed()
    }

    @Test fun `selecting_a_second_message_drops_single_message_actions`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.onNodeWithText("Perfect! I love Thai food.").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        rule.onNodeWithContentDescription("Forward").assertDoesNotExist()
    }

    @Test fun `delete_message_shows_confirm_then_removes_row`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete 1 message?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Meet you there at 12:30?").assertDoesNotExist()
    }

    @Test fun `forward_reports_message_body`() {
        var forwarded: String? = null
        rule.setContent { ThreadScreen(threadId = 1L, onForward = { forwarded = it }) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Forward").performClick()
        rule.waitForIdle()
        assertEquals("Meet you there at 12:30?", forwarded)
    }

    @Test fun `new_conversation_ime_done_reports_address`() {
        var entered: String? = null
        rule.setContent { NewConversationScreen(onAddressEntered = { entered = it }) }
        rule.onNodeWithText("Type a name, phone number, or email").performTextInput("+989121234567")
        rule.onNodeWithText("+989121234567", useUnmergedTree = true).performImeAction()
        rule.waitForIdle()
        assertEquals("+989121234567", entered)
    }

    @Test fun `new_conversation_recipient_field_accepts_input_and_filters`() {
        rule.setContent { NewConversationScreen() }
        // Regression: the field used value = "" with a no-op onValueChange,
        // so keystrokes were discarded (inactive InputConnection in logcat).
        rule.onNodeWithText("Type a name, phone number, or email").performTextInput("Ben")
        rule.waitForIdle()
        rule.onNodeWithText("Ben", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Alice Freeman").assertDoesNotExist()
    }
}

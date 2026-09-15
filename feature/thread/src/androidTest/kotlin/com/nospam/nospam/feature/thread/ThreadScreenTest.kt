package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
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

    @Test fun `thread_shows_fake_messages_and_sends_draft`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").assertIsDisplayed()
        rule.onNodeWithText("SMS message").performTextInput("See you!")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("See you!").assertIsDisplayed()
    }

    @Test fun `long_press_message_opens_action_menu`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Share").assertIsDisplayed()
        rule.onNodeWithText("Forward").assertIsDisplayed()
    }

    @Test fun `delete_message_shows_confirm_then_removes_row`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete message?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Meet you there at 12:30?").assertDoesNotExist()
    }

    @Test fun `forward_reports_message_body`() {
        var forwarded: String? = null
        rule.setContent { ThreadScreen(threadId = 1L, onForward = { forwarded = it }) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Forward").performClick()
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

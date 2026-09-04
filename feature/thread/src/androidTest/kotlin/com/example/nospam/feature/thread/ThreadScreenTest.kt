package com.example.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

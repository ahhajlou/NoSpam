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
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The recipient picker says what text is waiting for a recipient, when there is some. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class TextToSendPreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `shared text is shown above the recipient field`() {
        rule.setContent { NewConversationScreen(textToSend = "Look at this: https://example.com") }
        rule.onNodeWithText("Text to send").assertIsDisplayed()
        rule.onNodeWithText("Look at this: https://example.com").assertIsDisplayed()
        rule.onNodeWithText("To:").assertIsDisplayed()
    }

    @Test fun `several lines are shown as one`() {
        rule.setContent { NewConversationScreen(textToSend = "First line\nSecond line") }
        rule.onNodeWithText("First line Second line").assertIsDisplayed()
    }

    @Test fun `a plain new conversation has no preview`() {
        rule.setContent { NewConversationScreen() }
        assertTrue(rule.onAllNodesWithText("Text to send").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `blank text has no preview`() {
        rule.setContent { NewConversationScreen(textToSend = "   ") }
        assertTrue(rule.onAllNodesWithText("Text to send").fetchSemanticsNodes().isEmpty())
    }
}

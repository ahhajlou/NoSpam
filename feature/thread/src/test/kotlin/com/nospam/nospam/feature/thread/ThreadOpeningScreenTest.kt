// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the user sees when a conversation opens and while it is open: only that
 * conversation, new messages coming into view, and no reply box for a sender ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ThreadOpeningScreenTest {
    @get:Rule val rule = createComposeRule()

    private val thread = ThreadId(61)

    private fun incoming(id: Long, body: String, address: String = "+15550061") =
        Message(MessageId(id), thread, address, body, id * 1_000L, MessageType.INBOX, true)

    private fun history() = (1L..60L).map { incoming(it, "older message $it") }

    @Test fun `the first frame shows nothing from another conversation`() {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(thread, history())
        rule.mainClock.autoAdvance = false
        rule.setContent { ThreadScreen(threadId = 61L, viewModel = ThreadViewModel(fake)) }
        // The preview conversation the screen used to start with.
        assertTrue(rule.onAllNodesWithText("Perfect! I love Thai food.").fetchSemanticsNodes().isEmpty())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("older message 60").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Perfect! I love Thai food.").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the name the inbox showed is the title from the start`() {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(thread, history())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ThreadScreen(threadId = 61L, address = "+15550061", contactName = "Sara Q", viewModel = ThreadViewModel(fake))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Sara Q").assertIsDisplayed()
    }

    @Test fun `a message arriving while the newest is in view comes into view`() {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(thread, history())
        rule.setContent { ThreadScreen(threadId = 61L, viewModel = ThreadViewModel(fake)) }
        rule.waitForIdle()
        rule.onNodeWithText("older message 60").assertIsDisplayed()

        fake.emitMessages(thread, history() + incoming(61, "just arrived"))
        rule.waitForIdle()

        rule.onNodeWithText("just arrived").assertIsDisplayed()
    }

    @Test fun `a message arriving while reading far up does not move the list`() {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(thread, history())
        rule.setContent { ThreadScreen(threadId = 61L, viewModel = ThreadViewModel(fake)) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(60)
        rule.onNodeWithText("older message 1").assertIsDisplayed()

        fake.emitMessages(thread, history() + incoming(61, "just arrived"))
        rule.waitForIdle()

        rule.onNodeWithText("older message 1").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("just arrived").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `a sender ID has no reply box and no call action`() {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(thread, listOf(incoming(1, "Your bill is ready", address = "MCI")))
        rule.setContent { ThreadScreen(threadId = 61L, viewModel = ThreadViewModel(fake)) }
        rule.waitForIdle()

        rule.onNodeWithText("You can't reply to this sender").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("SMS message").fetchSemanticsNodes().isEmpty())
        assertEquals(0, rule.onAllNodesWithContentDescription("Call").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Your bill is ready").fetchSemanticsNodes().single()
    }

    @Test fun `a numeric short code can be replied to`() {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(thread, listOf(incoming(1, "Reply 11 to stop", address = "3000123")))
        rule.setContent { ThreadScreen(threadId = 61L, viewModel = ThreadViewModel(fake)) }
        rule.waitForIdle()

        rule.onNodeWithText("SMS message").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("You can't reply to this sender").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the recipient field opens on the keyboard and switches to the dial pad`() {
        rule.setContent { NewConversationScreen() }
        rule.onNodeWithContentDescription("Show dial pad").performClick()
        rule.onNodeWithContentDescription("Show keyboard").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("Show dial pad").assertIsDisplayed()
    }
}

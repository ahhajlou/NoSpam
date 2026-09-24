// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Thread screen behaviour on the JVM: Compose instrumented tests cannot run on
 * this project's API 37 emulator (TODO.md — Espresso reflects into a removed
 * platform method), so the same assertions run through Robolectric here.
 *
 * Robolectric's default window is 320x470px, too small to compose the list;
 * the qualifiers below are a normal phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ThreadScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `a failed message says it was not sent and retries when tapped`() {
        val fake = com.nospam.nospam.core.testing.FakeTelephonyDataSource()
        fake.emitMessages(
            com.nospam.nospam.core.model.ThreadId(9),
            listOf(
                com.nospam.nospam.core.model.Message(
                    com.nospam.nospam.core.model.MessageId(7), com.nospam.nospam.core.model.ThreadId(9),
                    "+15550009", "did not go", 1L, com.nospam.nospam.core.model.MessageType.FAILED, true,
                ),
            ),
        )
        val vm = ThreadViewModel(fake)
        rule.setContent { ThreadScreen(threadId = 9L, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("did not go").assertIsDisplayed()
        rule.onNodeWithText("Not sent · Tap to retry").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(listOf("did not go"), fake.sentMessages.map { it.second })
    }

    @Test fun `sending while scrolled up brings the sent message into view`() {
        val fake = com.nospam.nospam.core.testing.FakeTelephonyDataSource()
        val thread = com.nospam.nospam.core.model.ThreadId(61)
        fake.emitMessages(
            thread,
            (1..60).map {
                com.nospam.nospam.core.model.Message(
                    com.nospam.nospam.core.model.MessageId(it.toLong()), thread,
                    "+15550061", "older message $it", it * 1_000L, com.nospam.nospam.core.model.MessageType.INBOX, true,
                )
            },
        )
        rule.setContent { ThreadScreen(threadId = 61L, viewModel = ThreadViewModel(fake)) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(60)
        rule.onNodeWithText("older message 1").assertIsDisplayed()

        rule.onNodeWithText("SMS message").performTextInput("sent from far up")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("sent from far up").assertIsDisplayed()
    }

    @Test fun `a message that is still sending says so`() {
        val fake = com.nospam.nospam.core.testing.FakeTelephonyDataSource()
        fake.emitMessages(
            com.nospam.nospam.core.model.ThreadId(9),
            listOf(
                com.nospam.nospam.core.model.Message(
                    com.nospam.nospam.core.model.MessageId(7), com.nospam.nospam.core.model.ThreadId(9),
                    "+15550009", "on its way", 1L, com.nospam.nospam.core.model.MessageType.OUTBOX, true,
                ),
            ),
        )
        rule.setContent { ThreadScreen(threadId = 9L, viewModel = ThreadViewModel(fake)) }
        rule.waitForIdle()
        rule.onNodeWithText("Sending…").assertIsDisplayed()
    }

    @Test fun `the title shows the contact name and the conversation actions`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithContentDescription("Call").assertIsDisplayed()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Archive").assertIsDisplayed()
        rule.onNodeWithText("Block").assertIsDisplayed()
        rule.onNodeWithText("Delete conversation").assertIsDisplayed()
    }

    @Test fun `long press selects a message and shows its actions in the top bar`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        rule.onNodeWithContentDescription("Forward").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").assertIsDisplayed()
    }

    @Test fun `selecting a second message drops the single-message actions`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.onNodeWithText("Perfect! I love Thai food.").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Forward").assertDoesNotExist()
        rule.onNodeWithContentDescription("Copy").assertIsDisplayed()
    }

    @Test fun `forward reports the selected message body`() {
        var forwarded: String? = null
        rule.setContent { ThreadScreen(threadId = 1L, onForward = { forwarded = it }) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Forward").performClick()
        rule.waitForIdle()
        assertEquals("Meet you there at 12:30?", forwarded)
    }

    @Test fun `deleting messages asks for confirmation naming the count`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithText("Meet you there at 12:30?").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete 1 message?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Meet you there at 12:30?").assertIsDisplayed()
    }

    @Test fun `deleting the conversation confirms first and then reports the thread`() {
        var deleted: Long? = null
        rule.setContent { ThreadScreen(threadId = 1L, onDeleteConversation = { deleted = it }) }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Delete conversation").performClick()
        rule.waitForIdle()
        assertEquals(null, deleted)
        rule.onNodeWithText("Delete this conversation?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(1L, deleted)
    }

    @Test fun `send stays disabled until the draft has text`() {
        // Drafts are saved per thread id, so each typing test uses its own.
        rule.setContent { ThreadScreen(threadId = 41L) }
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onNodeWithText("SMS message").performTextInput("hi")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Send message").assertIsEnabled()
    }

    @Test fun `the emoji button swaps the keyboard for the picker and back`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        rule.onNodeWithContentDescription("Show emoji").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show keyboard").assertIsDisplayed()
        rule.onNodeWithContentDescription("Show keyboard").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show emoji").assertIsDisplayed()
    }

    @Test fun `the character counter appears only as a draft approaches the limit`() {
        rule.setContent { ThreadScreen(threadId = 42L) }
        rule.onNodeWithText("SMS message").performTextInput("short")
        rule.waitForIdle()
        rule.onNodeWithText("155/1", substring = true).assertDoesNotExist()

        // 150 latin characters: 10 left in a single 160-character part.
        rule.onNodeWithText("short").performTextInput("a".repeat(145))
        rule.waitForIdle()
        rule.onNodeWithText("10/1", substring = true).assertIsDisplayed()
    }

    @Test fun `going past one part shows the part count`() {
        rule.setContent { ThreadScreen(threadId = 43L) }
        rule.onNodeWithText("SMS message").performTextInput("a".repeat(161))
        rule.waitForIdle()
        rule.onNodeWithText("145/2", substring = true).assertIsDisplayed()
    }

    @Test fun `a typed message is sent and appears in the thread`() {
        rule.setContent { ThreadScreen(threadId = 44L) }
        rule.onNodeWithText("SMS message").performTextInput("See you!")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("See you!").assertIsDisplayed()
    }

    @Test fun `tapping a message reveals its time`() {
        rule.setContent { ThreadScreen(threadId = 1L) }
        // "Perfect! I love Thai food." carries no digits of its own, so any clock
        // time that appears after the tap is the timestamp row, not the body.
        val before = rule.clockTimeCount()
        rule.onNodeWithText("Perfect! I love Thai food.").performClick()
        rule.waitForIdle()
        assertEquals(before + 1, rule.clockTimeCount())
        // The bubble itself is unchanged.
        assertEquals(1, rule.onAllNodesWithText("Perfect! I love Thai food.").fetchSemanticsNodes().size)
    }

    @Test fun `the recipient picker reports the address on IME done`() {
        var entered: String? = null
        rule.setContent { NewConversationScreen(onAddressEntered = { entered = it }) }
        rule.onNodeWithText("Type a name, phone number, or email").performTextInput("+989121234567")
        rule.onNodeWithText("+989121234567", useUnmergedTree = true).performImeAction()
        rule.waitForIdle()
        assertEquals("+989121234567", entered)
    }

    @Test fun `the recipient picker filters contacts as you type`() {
        rule.setContent { NewConversationScreen() }
        // Regression: the field used value = "" with a no-op onValueChange, so
        // keystrokes were discarded (inactive InputConnection in logcat).
        rule.onNodeWithText("Type a name, phone number, or email").performTextInput("Ben")
        rule.waitForIdle()
        rule.onNodeWithText("Ben Carter").assertIsDisplayed()
        rule.onNodeWithText("Alice Freeman").assertDoesNotExist()
    }
}

private val CLOCK_TIME = Regex("""\d{1,2}:\d{2}""")

/** Number of nodes rendering something that looks like a clock time. */
private fun ComposeContentTestRule.clockTimeCount(): Int =
    onAllNodes(
        SemanticsMatcher("text looks like a clock time") { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { CLOCK_TIME.containsMatchIn(it.text) } == true
        }
    ).fetchSemanticsNodes().size

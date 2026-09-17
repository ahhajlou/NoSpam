package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
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
        rule.onNodeWithText("155/1").assertDoesNotExist()

        // 150 latin characters: 10 left in a single 160-character part.
        rule.onNodeWithText("short").performTextInput("a".repeat(145))
        rule.waitForIdle()
        rule.onNodeWithText("10/1").assertIsDisplayed()
    }

    @Test fun `going past one part shows the part count`() {
        rule.setContent { ThreadScreen(threadId = 43L) }
        rule.onNodeWithText("SMS message").performTextInput("a".repeat(161))
        rule.waitForIdle()
        rule.onNodeWithText("145/2").assertIsDisplayed()
    }
}

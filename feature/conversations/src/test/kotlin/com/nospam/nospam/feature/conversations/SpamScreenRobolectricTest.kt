// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** See CLAUDE.md §9 for why these run through Robolectric and need the qualifiers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SpamScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `spam rows are listed, and a blocked sender is labelled`() {
        rule.setContent { SpamScreen(title = "Spam & blocked") }
        rule.onNodeWithText("Win A Free Cruise!").assertIsDisplayed()
        rule.onNodeWithText("Blocked").assertIsDisplayed()
    }

    @Test fun `not spam from the selection removes the row and reports it`() {
        val reported = mutableListOf<Pair<Long, String>>()
        rule.setContent { SpamScreen(title = "Spam & blocked", onNotSpam = { reported.addAll(it) }) }
        rule.onNodeWithText("Win A Free Cruise!").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Not spam").performClick()
        rule.waitForIdle()
        assertEquals(listOf(201L to "Win A Free Cruise!"), reported)
        rule.onNodeWithText("Win A Free Cruise!").assertDoesNotExist()
    }

    @Test fun `spam rows do not swipe in either direction`() {
        val reported = mutableListOf<Pair<Long, String>>()
        rule.setContent { SpamScreen(title = "Spam & blocked", onNotSpam = { reported.addAll(it) }) }
        rule.onNodeWithText("Win A Free Cruise!").performTouchInput { swipeRight() }
        rule.onNodeWithText("Win A Free Cruise!").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(reported.isEmpty())
        rule.onNodeWithText("Win A Free Cruise!").assertIsDisplayed()
    }

    // See ArchivedScreenRobolectricTest: a slow swipe held past the long-press
    // timeout used to select the row.
    @Test fun `a slow drag across a row neither selects nor opens it`() {
        val opened = mutableListOf<Long>()
        rule.setContent { SpamScreen(title = "Spam & blocked", onConversationClick = { opened.add(it) }) }
        // Checked after each drag: a second drag would toggle a selection the
        // first one made back off, and hide it.
        for (towardEnd in listOf(true, false)) {
            rule.onNodeWithText("Win A Free Cruise!").performTouchInput { slowDragInside(towardEnd) }
            rule.waitForIdle()
            rule.onNodeWithText("1 selected").assertDoesNotExist()
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun `block asks for confirmation before blocking anything`() {
        val blocked = mutableListOf<String>()
        rule.setContent { SpamScreen(title = "Spam & blocked", onBlock = { blocked.addAll(it) }) }
        rule.onNodeWithText("Win A Free Cruise!").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Block").performClick()
        rule.waitForIdle()
        assertTrue(blocked.isEmpty())
        rule.onNodeWithText("Block 1 sender?").assertIsDisplayed()
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

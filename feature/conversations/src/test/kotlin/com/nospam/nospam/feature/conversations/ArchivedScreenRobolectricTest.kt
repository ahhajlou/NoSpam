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
class ArchivedScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `archived rows do not swipe in either direction`() {
        val unarchived = mutableListOf<Long>()
        rule.setContent { ArchivedScreen(title = "Archived", onUnarchive = { unarchived.addAll(it) }) }
        rule.onNodeWithText("Bank Alerts").performTouchInput { swipeRight() }
        rule.onNodeWithText("Bank Alerts").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(unarchived.isEmpty())
        rule.onNodeWithText("Bank Alerts").assertIsDisplayed()
    }

    @Test fun `unarchive from the selection removes the row and reports it`() {
        val unarchived = mutableListOf<Long>()
        rule.setContent { ArchivedScreen(title = "Archived", onUnarchive = { unarchived.addAll(it) }) }
        rule.onNodeWithText("Bank Alerts").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Unarchive").performClick()
        rule.waitForIdle()
        assertEquals(listOf(101L), unarchived)
        rule.onNodeWithText("Bank Alerts").assertDoesNotExist()
    }
}

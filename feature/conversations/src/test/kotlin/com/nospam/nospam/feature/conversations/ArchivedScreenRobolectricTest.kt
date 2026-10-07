// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.TouchInjectionScope
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

    // A drag held past the long-press timeout, as a slow swipe is. Compose
    // counts a finger that stays inside a row as a press however far it moves,
    // so without something to take the drag this selected the row (or, when
    // shorter, opened it).
    @Test fun `a slow drag across a row neither selects nor opens it`() {
        val opened = mutableListOf<Long>()
        rule.setContent { ArchivedScreen(title = "Archived", onConversationClick = { opened.add(it) }) }
        // Checked after each drag: a second drag would toggle a selection the
        // first one made back off, and hide it.
        for (towardEnd in listOf(true, false)) {
            rule.onNodeWithText("Bank Alerts").performTouchInput { slowDragInside(towardEnd) }
            rule.waitForIdle()
            rule.onNodeWithText("1 selected").assertDoesNotExist()
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun `a long-press held still still selects the row`() {
        rule.setContent { ArchivedScreen(title = "Archived") }
        rule.onNodeWithText("Bank Alerts").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
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

/**
 * A finger that drags sideways without leaving the row and is still down past
 * the long-press timeout, as at the end of a slow swipe. This is the gesture
 * that selected the row on the emulator. Compose's own swipeRight() ends on
 * the row's edge, which counts as leaving it and cancels the press, so it
 * never showed the bug.
 */
internal fun TouchInjectionScope.slowDragInside(towardEnd: Boolean) {
    val step = width * 0.1f * (if (towardEnd) 1 else -1)
    down(center)
    moveBy(androidx.compose.ui.geometry.Offset(step, 0f))
    moveBy(androidx.compose.ui.geometry.Offset(step, 0f))
    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
    moveBy(androidx.compose.ui.geometry.Offset(step, 0f))
    up()
}

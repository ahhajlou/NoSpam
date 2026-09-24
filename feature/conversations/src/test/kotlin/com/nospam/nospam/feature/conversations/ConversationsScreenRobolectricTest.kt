// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same screen behaviour as ConversationsScreenTest, but on the JVM:
 * Compose instrumented tests cannot run on this project's API 37 emulator
 * (TODO.md, Espresso reflects into a removed platform method).
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default window is 320x470px, too small to compose any row;
// this is a normal phone.
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ConversationsScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `long press enters selection mode with the actions in the top bar`() {
        rule.setContent { ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pin").assertIsDisplayed()
        rule.onNodeWithContentDescription("Archive").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").assertIsDisplayed()
    }

    @Test fun `tapping another row while selecting adds it instead of opening it`() {
        var opened: Long? = null
        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onConversationClick = { opened = it })
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.onNodeWithText("Delivery Driver").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        assertNull(opened)
    }

    @Test fun `archive acts on the selection and leaves selection mode`() {
        val archived = mutableListOf<Long>()
        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onArchive = { archived.addAll(it) })
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Archive").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2L), archived)
        rule.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test fun `delete asks for confirmation before deleting anything`() {
        val deleted = mutableListOf<Long>()
        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onDelete = { deleted.addAll(it) })
        }
        rule.onNodeWithText("Design Team Sync").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        assertEquals(emptyList<Long>(), deleted)
        rule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2L), deleted)
    }

    @Test fun `the inbox lists pinned and recent rows and offers the new-message button`() {
        var newMessageClicked = false
        rule.setContent {
            ConversationsScreen(
                title = "Inbox",
                viewModel = ConversationsViewModel(),
                onNewMessage = { newMessageClicked = true },
            )
        }
        rule.onNodeWithText("Alice Smith").assertIsDisplayed()
        rule.onNodeWithText("Pinned").assertIsDisplayed()
        rule.onNodeWithText("Recent").assertIsDisplayed()
        // Icon-only FAB: its label is the content description.
        rule.onNodeWithContentDescription("Start chat").assertIsDisplayed().performClick()
        assertTrue(newMessageClicked)
    }

    @Test fun `choosing a filter chip selects it`() {
        rule.setContent { ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Starred").assertIsDisplayed().performClick()
        rule.onNodeWithText("Starred").assertIsSelected()
    }

    @Test fun `the search field takes input`() {
        rule.setContent { ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel()) }
        rule.onNodeWithText("Search conversations").assertIsDisplayed().performClick()
        rule.onNodeWithText("Search conversations").performTextInput("alice")
        rule.onNodeWithText("alice", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `a searched number reads left to right and a Persian word right to left in a Persian layout`() {
        fun searchedDirection(query: String): ResolvedTextDirection {
            val field = rule.onNode(hasSetTextAction())
            field.performTextClearance()
            field.performTextInput(query)
            val layouts = mutableListOf<TextLayoutResult>()
            field.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            return layouts.first().getParagraphDirection(0)
        }
        val vm = ConversationsViewModel()
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ConversationsScreen(title = "Inbox", viewModel = vm)
            }
        }
        // Without its own direction "+98912" would be laid out right to left,
        // and the "+" would move to the other end.
        assertEquals(ResolvedTextDirection.Ltr, searchedDirection("+98912"))
        assertEquals(ResolvedTextDirection.Ltr, searchedDirection("meeting"))
        assertEquals(ResolvedTextDirection.Rtl, searchedDirection("جلسه"))
    }

    @Test fun `tapping a conversation reports its thread id`() {
        var clicked: Long? = null
        rule.setContent {
            ConversationsScreen(
                title = "Inbox",
                viewModel = ConversationsViewModel(),
                onConversationClick = { clicked = it },
            )
        }
        rule.onNodeWithText("Design Team Sync").performClick()
        assertEquals(2L, clicked)
    }
}

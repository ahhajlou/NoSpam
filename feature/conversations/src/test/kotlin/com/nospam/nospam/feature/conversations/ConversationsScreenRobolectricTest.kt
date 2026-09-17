package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onArchive = { archived.add(it) })
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
            ConversationsScreen(title = "Inbox", viewModel = ConversationsViewModel(), onDelete = { deleted.add(it) })
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
}

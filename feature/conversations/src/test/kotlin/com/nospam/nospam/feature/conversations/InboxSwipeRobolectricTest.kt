// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.SwipeAction
import com.nospam.nospam.core.model.SwipeActions
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Black-box tests for how the inbox (ConversationsScreen) reacts to a swipe,
 * per the configured SwipeActions, written from the task-brief spec
 * independently of ConversationsScreen.kt, ConversationList.kt and
 * ConversationsViewModel.kt. See CLAUDE.md §9 for why these run through
 * Robolectric and why the phone-sized qualifiers are required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class InboxSwipeRobolectricTest {
    @get:Rule val rule = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @Before fun setUp() { Dispatchers.setMain(mainDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun conv(id: Long, name: String, read: Boolean = true) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant(address = "+1555000$id", displayName = name)),
        snippet = "hi",
        date = System.currentTimeMillis(),
        messageCount = 1,
        read = read,
    )

    /** Repeatedly drains the (paused) Main scheduler while letting Compose settle,
     *  so a repository-backed load or DB write lands before the next assertion. */
    private fun settle() {
        repeat(10) {
            scheduler.advanceUntilIdle()
            rule.waitForIdle()
            Thread.sleep(5)
        }
    }

    private class Callbacks {
        val archived = mutableListOf<List<Long>>()
        val unarchived = mutableListOf<List<Long>>()
        val deleted = mutableListOf<List<Long>>()
        val setRead = mutableListOf<Pair<List<Long>, Boolean>>()
    }

    // --- ARCHIVE -----------------------------------------------------------

    @Test fun `an archive swipe calls onArchive and shows an undoable snackbar`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "Archive Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(
                title = "Inbox", viewModel = vm,
                onArchive = { cb.archived += it },
                onUnarchive = { cb.unarchived += it },
            )
        }
        settle()

        rule.onNodeWithText("Archive Contact").performTouchInput { swipeRight() }
        settle()

        assertEquals(listOf(listOf(1L)), cb.archived)
        rule.onNodeWithText("Conversation archived").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed()

        rule.onNodeWithText("Undo").performClick()
        settle()

        assertEquals(listOf(listOf(1L)), cb.unarchived)
    }

    @Test fun `after undo, the conversation reappearing does not get archived again`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "Archive Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(
                title = "Inbox", viewModel = vm,
                onArchive = { cb.archived += it },
                onUnarchive = { cb.unarchived += it },
            )
        }
        settle()

        rule.onNodeWithText("Archive Contact").performTouchInput { swipeRight() }
        settle()
        rule.onNodeWithText("Undo").performClick()
        settle()
        assertEquals(1, cb.archived.size)

        // Simulate the conversation reappearing (e.g. a fresh provider
        // emission): it must not be archived a second time just because it is
        // back in the list.
        telephony.emitConversations(listOf(conv(1, "Archive Contact")))
        settle()

        assertEquals(1, cb.archived.size)
    }

    // --- DELETE --------------------------------------------------------------

    @Test fun `a delete swipe asks for confirmation before deleting anything`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(2, "Delete Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = vm, onDelete = { cb.deleted += it })
        }
        settle()

        rule.onNodeWithText("Delete Contact").performTouchInput { swipeRight() }
        settle()

        assertTrue(cb.deleted.isEmpty())
        rule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()

        rule.onNodeWithText("Delete").performClick()
        settle()

        assertEquals(listOf(listOf(2L)), cb.deleted)
    }

    @Test fun `cancelling a delete swipe deletes nothing and keeps the row`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(2, "Delete Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = vm, onDelete = { cb.deleted += it })
        }
        settle()

        rule.onNodeWithText("Delete Contact").performTouchInput { swipeRight() }
        settle()
        rule.onNodeWithText("Cancel").performClick()
        settle()

        assertTrue(cb.deleted.isEmpty())
        rule.onNodeWithText("Delete Contact").assertIsDisplayed()
    }

    // --- TOGGLE_READ ---------------------------------------------------------

    @Test fun `a toggle-read swipe flips the row's read state, in either direction`() {
        val telephony = FakeTelephonyDataSource(
            listOf(conv(3, "Unread Contact", read = false), conv(4, "Read Contact", read = true)),
        )
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.TOGGLE_READ, left = SwipeAction.TOGGLE_READ)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = vm, onSetRead = { ids, read -> cb.setRead += ids to read })
        }
        settle()

        rule.onNodeWithText("Unread Contact").performTouchInput { swipeRight() }
        settle()
        rule.onNodeWithText("Read Contact").performTouchInput { swipeRight() }
        settle()

        assertEquals(listOf(listOf(3L) to true, listOf(4L) to false), cb.setRead)
        rule.onNodeWithText("Unread Contact").assertIsDisplayed()
        rule.onNodeWithText("Read Contact").assertIsDisplayed()
    }

    // --- NONE ------------------------------------------------------------------

    @Test fun `a NONE swipe direction does nothing`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(5, "None Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.NONE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(
                title = "Inbox", viewModel = vm,
                onSetRead = { ids, read -> cb.setRead += ids to read },
                onArchive = { cb.archived += it },
                onDelete = { cb.deleted += it },
                onUnarchive = { cb.unarchived += it },
            )
        }
        settle()

        rule.onNodeWithText("None Contact").performTouchInput { swipeRight() }
        settle()

        assertTrue(cb.archived.isEmpty() && cb.deleted.isEmpty() && cb.setRead.isEmpty() && cb.unarchived.isEmpty())
        rule.onNodeWithText("None Contact").assertIsDisplayed()
    }

    // --- Selection mode suppresses swipe --------------------------------------

    @Test fun `while selection mode is active, swiping does nothing`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "Archive Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = vm, onArchive = { cb.archived += it })
        }
        settle()

        rule.onNodeWithText("Archive Contact").performTouchInput { longClick() }
        settle()
        rule.onNodeWithText("1 selected").assertIsDisplayed()

        rule.onNodeWithText("Archive Contact").performTouchInput { swipeRight() }
        settle()

        assertTrue(cb.archived.isEmpty())
        rule.onNodeWithText("1 selected").assertIsDisplayed()
    }

    // --- Physical direction, independent of layout direction ------------------

    @Test fun `a physical left swipe uses the LEFT setting, independent of the right setting`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(7, "Left Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.DELETE)),
        )
        val cb = Callbacks()

        rule.setContent {
            ConversationsScreen(title = "Inbox", viewModel = vm, onArchive = { cb.archived += it }, onDelete = { cb.deleted += it })
        }
        settle()

        rule.onNodeWithText("Left Contact").performTouchInput { swipeLeft() }
        settle()

        rule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()
        assertTrue(cb.archived.isEmpty())
    }

    @Test fun `in RTL, a physical right swipe still triggers the RIGHT setting`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(6, "RTL Contact")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ConversationsViewModel(
            repository = repo,
            swipeActions = MutableStateFlow(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.ARCHIVE)),
        )
        val cb = Callbacks()

        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ConversationsScreen(title = "Inbox", viewModel = vm, onDelete = { cb.deleted += it }, onArchive = { cb.archived += it })
            }
        }
        settle()

        rule.onNodeWithText("RTL Contact").performTouchInput { swipeRight() }
        settle()

        // Right setting is DELETE, not ARCHIVE: the physical-right swipe
        // triggered the RIGHT action even though the layout is RTL.
        rule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()
        assertTrue(cb.archived.isEmpty())
    }
}

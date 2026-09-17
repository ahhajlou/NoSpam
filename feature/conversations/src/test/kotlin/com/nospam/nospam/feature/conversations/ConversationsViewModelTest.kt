package com.nospam.nospam.feature.conversations

import app.cash.turbine.test
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ConversationFilter
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behaviour tests for ConversationsViewModel per feature-conversations.md,
 * driven entirely through an injected fake (FakeTelephonyDataSource +
 * ConversationsRepository backed by an in-memory database) rather than the
 * ViewModel's hardcoded preview data — see Wave 2A task brief: the previous
 * suite asserted on preview literals ("Alice Smith" etc.), which is asserting
 * on incidental copy rather than behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun conv(id: Long, snippet: String, read: Boolean = true, pinned: Boolean = false) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant("+100$id")),
        snippet = snippet,
        date = System.currentTimeMillis(),
        messageCount = 1,
        read = read,
        isPinned = pinned,
    )

    private fun liveVm(vararg convs: Conversation): ConversationsViewModel {
        // Pass the test scope so the repository's flag combine runs on the test
        // dispatcher instead of hopping to IO, which advanceUntilIdle cannot see.
        val repo = ConversationsRepository(
            FakeTelephonyDataSource(convs.toList()),
            NoSpamDatabase.inMemory(),
            CoroutineScope(testDispatcher),
        )
        return ConversationsViewModel(repo)
    }

    @Test fun `pinned conversations are excluded from the main list with no duplicate ids`() = runTest {
        // Pinning is app-owned state layered on by the repository (db.pinnedDao),
        // not a field the raw telephony source can set directly.
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "pinned one"), conv(2, "regular two")))
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(telephony, db, CoroutineScope(testDispatcher))
        db.pinnedDao.pin(1)
        val vm = ConversationsViewModel(repo)
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertEquals(1, state.pinned.size)
            assertEquals(1, state.conversations.size)
            val allIds = (state.pinned + state.conversations).map { it.threadId.value }
            assertEquals(allIds.size, allIds.toSet().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `filter selection updates state`() = runTest {
        val vm = liveVm(conv(1, "hello"))
        vm.uiState.test {
            expectMostRecentItem()
            vm.onFilterSelected(ConversationFilter.STARRED)
            assertEquals(ConversationFilter.STARRED, expectMostRecentItem().filter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `search query and focus update state`() = runTest {
        val vm = liveVm(conv(1, "hello world"))
        vm.uiState.test {
            expectMostRecentItem()
            vm.onSearchQueryChanged("alice")
            assertEquals("alice", expectMostRecentItem().searchQuery)
            vm.onSearchFocusChanged(true)
            assertTrue(expectMostRecentItem().isSearchFocused)
            vm.onSearchFocusChanged(false)
            assertFalse(expectMostRecentItem().isSearchFocused)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `live vm serves provider conversations`() = runTest {
        val vm = liveVm(conv(1, "hello world"), conv(2, "another one"))
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(vm.isLive)
            assertEquals(2, state.conversations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `live vm filters by search query`() = runTest {
        val vm = liveVm(conv(1, "hello world"), conv(2, "another one"))
        vm.uiState.test {
            expectMostRecentItem()
            vm.onSearchQueryChanged("hello")
            val state = expectMostRecentItem()
            assertEquals(1, state.conversations.size)
            assertEquals("hello world", state.conversations.first().snippet)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `live vm unread filter hides read threads`() = runTest {
        val vm = liveVm(conv(1, "unread msg", read = false), conv(2, "read msg", read = true))
        vm.uiState.test {
            expectMostRecentItem()
            vm.onFilterSelected(ConversationFilter.UNREAD)
            val state = expectMostRecentItem()
            assertEquals(ConversationFilter.UNREAD, state.filter)
            assertEquals(1, state.conversations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

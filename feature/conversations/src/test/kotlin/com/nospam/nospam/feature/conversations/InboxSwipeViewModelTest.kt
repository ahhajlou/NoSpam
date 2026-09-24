// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import app.cash.turbine.test
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.SwipeAction
import com.nospam.nospam.core.model.SwipeActions
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for ConversationsViewModel's new `swipeActions: Flow<SwipeActions>?`
 * constructor parameter and the derived `uiState.value.swipeActions`,
 * written from the task-brief spec independently of ConversationsViewModel.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxSwipeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun conv(id: Long) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant("+100$id")),
        snippet = "hi",
        date = System.currentTimeMillis(),
        messageCount = 1,
        read = true,
    )

    private fun repoWith(vararg convs: Conversation) = ConversationsRepository(
        FakeTelephonyDataSource(convs.toList()),
        NoSpamDatabase.inMemory(),
        CoroutineScope(testDispatcher),
    )

    @Test fun `with no swipeActions flow the state defaults to archive-archive`() = runTest {
        val vm = ConversationsViewModel(repository = repoWith(conv(1)))
        vm.uiState.test {
            assertEquals(SwipeActions(), expectMostRecentItem().swipeActions)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `the preview, no-repository constructor also defaults swipeActions`() = runTest {
        val vm = ConversationsViewModel()
        assertEquals(SwipeActions(), vm.uiState.value.swipeActions)
    }

    @Test fun `uiState swipeActions follows the injected flow's initial value`() = runTest {
        val flow = MutableStateFlow(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.NONE))
        val vm = ConversationsViewModel(repository = repoWith(conv(1)), swipeActions = flow)
        vm.uiState.test {
            assertEquals(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.NONE), expectMostRecentItem().swipeActions)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `uiState swipeActions follows later emissions from the flow`() = runTest {
        val flow = MutableStateFlow(SwipeActions())
        val vm = ConversationsViewModel(repository = repoWith(conv(1)), swipeActions = flow)
        vm.uiState.test {
            expectMostRecentItem()
            flow.value = SwipeActions(right = SwipeAction.TOGGLE_READ, left = SwipeAction.DELETE)
            assertEquals(
                SwipeActions(right = SwipeAction.TOGGLE_READ, left = SwipeAction.DELETE),
                expectMostRecentItem().swipeActions,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}

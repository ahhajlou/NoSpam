// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import app.cash.turbine.test
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ArchivedViewModel's own KDoc claims archived threads are always empty
 * ("future work"). That is stale: core:database's ArchivedDao and
 * ConversationsRepository.archive()/observeArchived() are already fully wired
 * (see core-data.md, feature-conversations.md contradiction #1). These tests
 * assert the real, current behaviour -- not the KDoc's claim -- per the task
 * brief's explicit instruction not to encode the stale comment as a test.
 *
 * `conversations` is `StateFlow<List<Conversation>?>`: null until the first
 * repository emission arrives, then the loaded (possibly empty) list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchivedViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun conv(id: Long) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant("+9891$id")),
        snippet = "x",
        date = 1L,
        messageCount = 1,
        read = true,
    )

    @Test
    fun `conversations reflects a thread archived through the repository`() = runTest {
        val telephony = FakeTelephonyDataSource(listOf(conv(1), conv(2)))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory(), CoroutineScope(testDispatcher))
        repo.archive(ThreadId(1))

        val vm = ArchivedViewModel(repo)
        vm.conversations.test {
            var state = awaitItem()
            while (state == null || state.isEmpty()) state = awaitItem()
            assertEquals(listOf(1L), state.map { it.threadId.value })
            assertTrue(state.first().isArchived)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conversations is null before the first load, then reflects the archived thread`() = runTest {
        // Deliberately StandardTestDispatcher, not Unconfined: nothing about this
        // test's assertions should depend on the eager Unconfined dispatch this
        // file otherwise uses, so the null-before-load window is observed for
        // real rather than skipped over by eager execution.
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val telephony = FakeTelephonyDataSource(listOf(conv(1), conv(2)))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory(), CoroutineScope(dispatcher))
        repo.archive(ThreadId(1))

        val vm = ArchivedViewModel(repo)
        assertNull(vm.conversations.value)

        advanceUntilIdle()

        vm.conversations.test {
            var state = awaitItem()
            while (state == null) state = awaitItem()
            assertEquals(listOf(1L), state.map { it.threadId.value })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conversations is an empty, non-null list once loaded when nothing has been archived`() = runTest {
        val telephony = FakeTelephonyDataSource(listOf(conv(1)))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory(), CoroutineScope(testDispatcher))

        val vm = ArchivedViewModel(repo)
        vm.conversations.test {
            var state = awaitItem()
            while (state == null) state = awaitItem()
            assertEquals(emptyList<Conversation>(), state)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

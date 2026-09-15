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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
            while (state.isEmpty()) state = awaitItem()
            assertEquals(listOf(1L), state.map { it.threadId.value })
            assertTrue(state.first().isArchived)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conversations is empty when nothing has been archived`() = runTest {
        val telephony = FakeTelephonyDataSource(listOf(conv(1)))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory(), CoroutineScope(testDispatcher))

        val vm = ArchivedViewModel(repo)
        assertEquals(emptyList<Conversation>(), vm.conversations.value)
    }
}

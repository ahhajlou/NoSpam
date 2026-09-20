// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import app.cash.turbine.test
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
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
import org.junit.Before
import org.junit.Test

/**
 * SpamViewModel had zero tests before Wave 2A (task brief + feature-conversations.md).
 * Its KDoc ("Null repository -> null flow") does not match its non-nullable
 * constructor -- the testable contract is simply a pass-through of
 * ConversationsRepository.observeSpam(), per the spec.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpamViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun conv(id: Long, address: String) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant(address)),
        snippet = "x",
        date = 1L,
        messageCount = 1,
        read = true,
    )

    private fun repository(vararg convs: Conversation): ConversationsRepository {
        val telephony = FakeTelephonyDataSource(convs.toList())
        return ConversationsRepository(telephony, NoSpamDatabase.inMemory(), CoroutineScope(testDispatcher))
    }

    @Test
    fun `conversations reflects only spam and blocked senders from the repository`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource(
            listOf(conv(1, "+98911"), conv(2, "+98922"), conv(3, "+98933"))
        )
        val repo = ConversationsRepository(telephony, db, CoroutineScope(testDispatcher))
        db.senderStateDao.upsert(SenderStateEntity(normalizedAddress = "+98911", state = ThreadSpamState.SPAM))
        db.senderStateDao.upsert(SenderStateEntity(normalizedAddress = "+98922", state = ThreadSpamState.CLEAN))
        db.senderStateDao.upsert(SenderStateEntity(normalizedAddress = "+98933", state = ThreadSpamState.BLOCKED))

        val vm = SpamViewModel(repo)
        vm.conversations.test {
            var state = awaitItem()
            while (state.isEmpty()) state = awaitItem()
            assertEquals(setOf(1L, 3L), state.map { it.threadId.value }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conversations is empty when no sender is flagged`() = runTest {
        val vm = SpamViewModel(repository(conv(1, "+98911")))
        assertEquals(emptyList<Conversation>(), vm.conversations.value)
    }
}

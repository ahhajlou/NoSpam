package com.example.nospam.feature.conversations

import com.example.nospam.core.data.ConversationsRepository
import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.ConversationFilter
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.Participant
import com.example.nospam.core.model.ThreadId
import com.example.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConversationsViewModelTest {
    private class FakeTelephony(convs: List<Conversation>) : TelephonyDataSource {
        private val flow = MutableStateFlow(convs)
        override fun observeMessages(threadId: ThreadId): Flow<List<Message>> = MutableStateFlow(emptyList())
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override suspend fun getConversations(): List<Conversation> = flow.value
        override suspend fun getMessages(threadId: ThreadId): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> =
            Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun markAsUnread(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long? = 1L
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? = 2L
        override suspend fun getOrCreateThreadId(address: String): Long = 1L
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun liveVm(vararg convs: Conversation): ConversationsViewModel {
        val repo = ConversationsRepository(FakeTelephony(convs.toList()), NoSpamDatabase.inMemory())
        return ConversationsViewModel(repo)
    }

    private fun conv(id: Long, snippet: String, read: Boolean = true) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant("+100$id")),
        snippet = snippet,
        date = System.currentTimeMillis(),
        messageCount = 1,
        read = read,
    )

    @Test fun `initial state has pinned item excluded from main list`() {
        val vm = ConversationsViewModel()
        val state = vm.uiState.value
        assertEquals(1, state.pinned.size)
        assertEquals(5, state.conversations.size)
        // Regression: pinned ThreadId(1) once appeared in both lists, crashing
        // LazyColumn with duplicate key "1".
        val allIds = (state.pinned + state.conversations).map { it.threadId.value }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test fun `filter selection updates state`() {
        val vm = ConversationsViewModel()
        assertEquals(ConversationFilter.ALL, vm.uiState.value.filter)
        vm.onFilterSelected(ConversationFilter.STARRED)
        assertEquals(ConversationFilter.STARRED, vm.uiState.value.filter)
    }

    @Test fun `search query and focus update state`() {
        val vm = ConversationsViewModel()
        vm.onSearchQueryChanged("alice")
        assertEquals("alice", vm.uiState.value.searchQuery)
        vm.onSearchFocusChanged(true)
        assertTrue(vm.uiState.value.isSearchFocused)
        vm.onSearchFocusChanged(false)
        assertFalse(vm.uiState.value.isSearchFocused)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `live vm serves provider conversations`() = runTest {
        val vm = liveVm(conv(1, "hello world"), conv(2, "another one"))
        // WhileSubscribed needs a collector before the flow emits.
        val collected = mutableListOf<ConversationsUiState>()
        val job = launch(testDispatcher) { vm.uiState.collect { collected.add(it) } }
        testScheduler.advanceUntilIdle()
        val latest = collected.last()
        assertTrue(vm.isLive)
        assertEquals(2, latest.conversations.size)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `live vm filters by search query`() = runTest {
        val vm = liveVm(conv(1, "hello world"), conv(2, "another one"))
        val collected = mutableListOf<ConversationsUiState>()
        val job = launch(testDispatcher) { vm.uiState.collect { collected.add(it) } }
        vm.onSearchQueryChanged("hello")
        testScheduler.advanceUntilIdle()
        val latest = collected.last()
        assertEquals(1, latest.conversations.size)
        assertEquals("hello world", latest.conversations.first().snippet)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `live vm unread filter hides read threads`() = runTest {
        val vm = liveVm(conv(1, "unread msg", read = false), conv(2, "read msg", read = true))
        val collected = mutableListOf<ConversationsUiState>()
        val job = launch(testDispatcher) { vm.uiState.collect { collected.add(it) } }
        vm.onFilterSelected(ConversationFilter.UNREAD)
        testScheduler.advanceUntilIdle()
        val latest = collected.last()
        assertEquals(ConversationFilter.UNREAD, latest.filter)
        assertEquals(1, latest.conversations.size)
        job.cancel()
    }
}

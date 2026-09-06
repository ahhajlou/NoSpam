package com.nospam.nospam.feature.thread

import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
    private class FakeTelephony(
        /** Simulates the provider quirk where a reload misses the just-written row. */
        var hideSentFromQuery: Boolean = false,
    ) : TelephonyDataSource {
        val store = mutableListOf(
            Message(MessageId(1), ThreadId(9), "+1555", "hi from device", 1L, MessageType.INBOX, false)
        )
        val sent = mutableListOf<Triple<String, String, Int?>>()
        val markedRead = mutableListOf<Long>()
        private val flow = MutableStateFlow<List<Conversation>>(emptyList())
        private val messageTick = MutableStateFlow(0)
        fun emitMessages() { messageTick.value++ }
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override fun observeMessages(threadId: ThreadId): Flow<List<Message>> =
            messageTick.map { store.filter { it.threadId == threadId } }
        override suspend fun getConversations(): List<Conversation> = emptyList()
        override suspend fun getMessages(threadId: ThreadId): List<Message> =
            store.filter { it.threadId == threadId }
                .filterNot { hideSentFromQuery && it.type == MessageType.SENT }
        override suspend fun markAsRead(threadId: ThreadId) { markedRead.add(threadId.value) }
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> {
            sent.add(Triple(address, body, subscriptionId))
            return Result.success(Unit)
        }
        override suspend fun markAsUnread(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long? = 1L
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
            store.add(Message(MessageId(2), ThreadId(9), address, body, date, MessageType.SENT, true))
            return 2L
        }
        override suspend fun getOrCreateThreadId(address: String): Long = 9L
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state has fake messages`() {
        val vm = ThreadViewModel()
        assertTrue(vm.uiState.value.messages.isNotEmpty())
    }

    @Test fun `loadThread sets thread id`() {
        val vm = ThreadViewModel()
        vm.loadThread(42L)
        assertEquals(42L, vm.uiState.value.threadId)
    }

    @Test fun `send appends sent message and clears draft`() {
        val vm = ThreadViewModel()
        vm.loadThread(1L)
        val before = vm.uiState.value.messages.size
        vm.onDraftChanged("hello there")
        vm.onSend()
        val state = vm.uiState.value
        assertEquals("", state.draft)
        assertEquals(before + 1, state.messages.size)
        val last = state.messages.last()
        assertEquals("hello there", last.body)
        assertEquals(MessageType.SENT, last.type)
    }

    @Test fun `blank draft does not send`() {
        val vm = ThreadViewModel()
        vm.loadThread(1L)
        val before = vm.uiState.value.messages.size
        vm.onDraftChanged("   ")
        vm.onSend()
        assertEquals(before, vm.uiState.value.messages.size)
    }

    @Test fun `live vm loads provider messages`() {
        val vm = ThreadViewModel(FakeTelephony())
        vm.loadThread(9L)
        val state = vm.uiState.value
        assertEquals(9L, state.threadId)
        assertEquals(1, state.messages.size)
        assertEquals("hi from device", state.messages.first().body)
    }

    @Test fun `live vm sends via SmsManager and persists sent copy`() {
        val telephony = FakeTelephony()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        vm.onDraftChanged("reply text")
        vm.onSend()
        assertEquals(listOf(Triple("+1555", "reply text", null)), telephony.sent)
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.messages.any { it.body == "reply text" && it.type == MessageType.SENT })
    }

    @Test fun `incoming message appears without reopening`() {
        // The reported bug: provider emissions must reach the open thread.
        val telephony = FakeTelephony()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertEquals(1, vm.uiState.value.messages.size)
        telephony.store.add(
            Message(MessageId(9), ThreadId(9), "+1555", "fresh hello", 5L, MessageType.INBOX, false)
        )
        telephony.emitMessages()
        val state = vm.uiState.value
        assertEquals(2, state.messages.size)
        assertTrue(state.messages.any { it.body == "fresh hello" })
    }

    @Test fun `opening thread with unread marks read once`() {
        val telephony = FakeTelephony()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertEquals(listOf(9L), telephony.markedRead)
    }

    @Test fun `all-read thread does not mark`() {
        val telephony = FakeTelephony()
        telephony.store[0] = telephony.store[0].copy(read = true)
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertTrue(telephony.markedRead.isEmpty())
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test fun `sent bubble survives a stale provider reload`() {
        // Reproduces the device bug: the reload right after the sent-box
        // write missed the new row, leaving the thread looking stale until
        // reopened. The optimistic row must stay visible.
        val telephony = FakeTelephony(hideSentFromQuery = true)
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        vm.onDraftChanged("reply text")
        vm.onSend()
        val state = vm.uiState.value
        assertEquals("", state.draft)
        assertEquals(1, state.messages.count { it.body == "reply text" })
        assertEquals(MessageType.SENT, state.messages.last().type)
    }
}

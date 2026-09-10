package com.nospam.nospam.feature.thread

import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
            messageTick.map {
                store.filter { it.threadId == threadId }.sortedBy { it.id.value }.takeLast(TelephonyDataSource.MESSAGES_PAGE_SIZE)
            }
        override suspend fun getConversations(): List<Conversation> = emptyList()
        override suspend fun getMessages(threadId: ThreadId, limit: Int, beforeId: Long?): List<Message> {
            val eligible = store.filter { it.threadId == threadId }
                .filterNot { hideSentFromQuery && it.type == MessageType.SENT }
                .sortedBy { it.id.value }
            return if (beforeId != null) eligible.filter { it.id.value < beforeId }.takeLast(limit)
            else eligible.takeLast(limit)
        }
        override suspend fun markAsRead(threadId: ThreadId) { markedRead.add(threadId.value) }
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> {
            sent.add(Triple(address, body, subscriptionId))
            return Result.success(Unit)
        }
        override suspend fun markAsUnread(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?): Long? = 1L
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
            store.add(Message(MessageId(2), ThreadId(9), address, body, date, MessageType.SENT, true))
            return 2L
        }
        override suspend fun getContacts(limit: Int, query: String?): List<com.nospam.nospam.core.model.ContactEntry> = emptyList()
        override suspend fun searchBodyMatch(query: String): Set<Long> = emptySet()
        override suspend fun getActiveSubscriptions(): List<com.nospam.nospam.core.telephony.TelephonyDataSource.SimInfo> = emptyList()
        override suspend fun hasOutboundMessages(threadId: com.nospam.nospam.core.model.ThreadId): Boolean = false
        override suspend fun getOutboundSenderAddresses(): Set<String> = emptySet()
        override suspend fun lookupContact(address: String): com.nospam.nospam.core.model.Participant? = null
        override suspend fun isSystemBlocked(address: String): Boolean = false
        override suspend fun updateMessageRead(messageId: Long, read: Boolean) {}
        override suspend fun getOrCreateThreadId(address: String): Long = 9L
        override suspend fun getAllMessages(): List<Message> = store.toList()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state has fake messages`() {
        val vm = ThreadViewModel()
        assertTrue(vm.uiState.value.messages.isNotEmpty())
    }

    @Test fun `loadThread exposes per-message spam ids and not-spam action updates live`() = runTest {
        // StandardTestDispatcher makes the verdict collector + action deterministic.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val db = NoSpamDatabase.inMemory()
        val hamClassifier = object : SpamClassifier {
            override suspend fun classify(message: RawMessage) = SpamVerdict(SpamLabel.HAM, 0.0)
            override suspend fun classifyText(text: String) = SpamVerdict(SpamLabel.HAM, 0.0)
        }
        val repo = SpamRepository(db, hamClassifier)
        db.messageVerdictDao.insert(
            MessageVerdictEntity(messageId = 1L, threadId = 9L, normalizedAddress = "+1555", isSpam = true, score = 0.9, createdAt = 1L)
        )
        db.senderStateDao.upsert(SenderStateEntity("+1555", ThreadSpamState.MIXED, spamCount = 1, hamCount = 1))

        val vm = ThreadViewModel(FakeTelephony(), spamRepository = repo)
        vm.loadThread(9L)
        advanceUntilIdle()
        assertTrue(1L in vm.uiState.value.spamMessageIds)
        assertNotNull(vm.uiState.value.onMarkNotSpam)

        vm.uiState.value.onMarkNotSpam!!.invoke(1L)
        advanceUntilIdle()
        assertEquals(false, db.messageVerdictDao.getByMessageId(1L)?.userLabel)
        assertTrue(1L !in vm.uiState.value.spamMessageIds)
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

    @Test fun `long thread opens with newest page and loads older on demand`() {
        // 251 messages (ids 1..251) → newest page = ids 52..251; loading older
        // prepends 1..51 (what the ≥200-msg truncation used to hide).
        val telephony = FakeTelephony()
        for (i in 2L..251L) {
            telephony.store.add(Message(MessageId(i), ThreadId(9), "+1555", "m$i", i, MessageType.INBOX, true))
        }
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertEquals(200, vm.uiState.value.messages.size)
        assertEquals(52L, vm.uiState.value.messages.first().id.value)
        assertTrue(vm.uiState.value.hasMoreOlder)

        vm.loadOlder()
        assertEquals(251, vm.uiState.value.messages.size)
        assertEquals(1L, vm.uiState.value.messages.first().id.value)
        assertFalse(vm.uiState.value.hasMoreOlder)
    }

    @Test fun `loadOlder no-ops for short threads`() {
        val vm = ThreadViewModel(FakeTelephony())
        vm.loadThread(9L)
        val size = vm.uiState.value.messages.size
        vm.loadOlder()
        assertEquals(size, vm.uiState.value.messages.size)
        assertFalse(vm.uiState.value.hasMoreOlder)
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

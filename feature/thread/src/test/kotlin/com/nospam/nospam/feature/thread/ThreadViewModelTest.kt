package com.nospam.nospam.feature.thread

import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Behaviour tests for ThreadViewModel per feature-thread.md, driven through
 * the shared FakeTelephonyDataSource (extended in core:testing with
 * markedReadThreadIds/sentMessages tracking this suite needed) rather than a
 * hand-rolled local fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {

    private fun telephonyWithThread9(vararg messages: Message = arrayOf(
        Message(MessageId(1), ThreadId(9), "+1555", "hi from device", 1L, MessageType.INBOX, false)
    )): FakeTelephonyDataSource {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), messages.toList())
        fake.nextThreadId = 9L
        return fake
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
        val repo = SpamRepository(db, FakeSpamClassifier.alwaysHam())
        db.messageVerdictDao.insert(
            MessageVerdictEntity(messageId = 1L, threadId = 9L, normalizedAddress = "+1555", isSpam = true, score = 0.9, createdAt = 1L)
        )
        db.senderStateDao.upsert(SenderStateEntity("+1555", ThreadSpamState.MIXED, spamCount = 1, hamCount = 1))

        val vm = ThreadViewModel(telephonyWithThread9(), spamRepository = repo)
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
        val vm = ThreadViewModel(telephonyWithThread9())
        vm.loadThread(9L)
        val state = vm.uiState.value
        assertEquals(9L, state.threadId)
        assertEquals(1, state.messages.size)
        assertEquals("hi from device", state.messages.first().body)
    }

    @Test fun `live vm sends via SmsManager and persists sent copy`() {
        val telephony = telephonyWithThread9()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        vm.onDraftChanged("reply text")
        vm.onSend()
        assertEquals(listOf(Triple("+1555", "reply text", null)), telephony.sentMessages)
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.messages.any { it.body == "reply text" && it.type == MessageType.SENT })
    }

    @Test fun `incoming message appears without reopening`() {
        // The reported bug: provider emissions must reach the open thread.
        val telephony = telephonyWithThread9()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertEquals(1, vm.uiState.value.messages.size)

        telephony.emitMessages(
            ThreadId(9),
            listOf(
                Message(MessageId(1), ThreadId(9), "+1555", "hi from device", 1L, MessageType.INBOX, false),
                Message(MessageId(9), ThreadId(9), "+1555", "fresh hello", 5L, MessageType.INBOX, false),
            ),
        )
        val state = vm.uiState.value
        assertEquals(2, state.messages.size)
        assertTrue(state.messages.any { it.body == "fresh hello" })
    }

    @Test fun `long thread opens with newest page and loads older on demand`() {
        // 251 messages (ids 1..251) -> newest page = ids 52..251; loading older
        // prepends 1..51 (what the >=200-msg truncation used to hide).
        val messages = (1L..251L).map {
            Message(MessageId(it), ThreadId(9), "+1555", "m$it", it, MessageType.INBOX, true)
        }
        val telephony = telephonyWithThread9(*messages.toTypedArray())
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
        val vm = ThreadViewModel(telephonyWithThread9())
        vm.loadThread(9L)
        val size = vm.uiState.value.messages.size
        vm.loadOlder()
        assertEquals(size, vm.uiState.value.messages.size)
        assertFalse(vm.uiState.value.hasMoreOlder)
    }

    @Test fun `opening thread with unread marks read once`() {
        val telephony = telephonyWithThread9()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertEquals(listOf(9L), telephony.markedReadThreadIds)
    }

    @Test fun `all-read thread does not mark`() {
        val telephony = telephonyWithThread9(
            Message(MessageId(1), ThreadId(9), "+1555", "hi from device", 1L, MessageType.INBOX, true),
        )
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertTrue(telephony.markedReadThreadIds.isEmpty())
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test fun `onDeleteMessage removes the row and records the id on the fake`() {
        val telephony = telephonyWithThread9()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        assertEquals(1, vm.uiState.value.messages.size)

        vm.onDeleteMessage(1L)
        assertEquals(listOf(1L), telephony.deletedMessageIds)
        assertTrue(vm.uiState.value.messages.none { it.id.value == 1L })
    }

    @Test fun `loadThread with forwardBody pre-fills the draft`() {
        val vm = ThreadViewModel(telephonyWithThread9())
        vm.loadThread(9L, forwardBody = "fwd text")
        assertEquals("fwd text", vm.uiState.value.draft)
    }

    @Test fun `optimistic sent bubble survives before the provider reconciles it`() {
        // The optimistic row ThreadViewModel appends locally on send must stay
        // visible immediately, before any provider re-emission arrives.
        val telephony = telephonyWithThread9()
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

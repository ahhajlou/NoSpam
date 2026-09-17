package com.nospam.nospam.feature.thread

import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
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

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelDeleteForwardTest {

    private fun telephonyWithThread9(vararg messages: Message = arrayOf(
        Message(MessageId(1), ThreadId(9), "+1555", "hi from device", 1L, MessageType.INBOX, false)
    )): FakeTelephonyDataSource {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), messages.toList())
        fake.nextThreadId = 9L
        return fake
    }

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `live path delete reconciles through the data source once the scheduler is advanced`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val telephony = telephonyWithThread9()
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.messages.size)

        vm.onDeleteMessage(1L)
        advanceUntilIdle()

        assertEquals(listOf(1L), telephony.deletedMessageIds)
        assertTrue(vm.uiState.value.messages.none { it.id.value == 1L })
    }

    @Test fun `preview path delete removes the row synchronously with no data source involved`() {
        val vm = ThreadViewModel()
        val before = vm.uiState.value.messages
        assertTrue(before.isNotEmpty())
        val target = before.first().id.value

        vm.onDeleteMessage(target)

        assertEquals(before.size - 1, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.messages.none { it.id.value == target })
    }

    @Test fun `deleting an id absent from the thread is a safe no-op on the preview path`() {
        val vm = ThreadViewModel()
        val before = vm.uiState.value.messages

        vm.onDeleteMessage(-999L)

        assertEquals(before, vm.uiState.value.messages)
    }

    @Test fun `loadThread with forwardBody prefills the draft synchronously`() {
        val vm = ThreadViewModel(telephonyWithThread9())

        vm.loadThread(9L, forwardBody = "check this out")

        assertEquals("check this out", vm.uiState.value.draft)
    }

    @Test fun `loadThread without forwardBody leaves the draft empty on a fresh vm`() {
        val vm = ThreadViewModel(telephonyWithThread9())

        vm.loadThread(9L)

        assertEquals("", vm.uiState.value.draft)
    }

    @Test fun `a forwarded draft does not leak into a later plain reload of a different thread`() {
        val vm = ThreadViewModel()

        vm.loadThread(1L, forwardBody = "forwarded text")
        assertEquals("forwarded text", vm.uiState.value.draft)

        vm.loadThread(2L)

        assertEquals("", vm.uiState.value.draft)
    }
}

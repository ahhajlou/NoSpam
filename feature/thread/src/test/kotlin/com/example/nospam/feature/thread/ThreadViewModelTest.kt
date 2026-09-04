package com.example.nospam.feature.thread

import com.example.nospam.core.model.MessageType
import org.junit.Assert.*
import org.junit.Test

class ThreadViewModelTest {
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
}

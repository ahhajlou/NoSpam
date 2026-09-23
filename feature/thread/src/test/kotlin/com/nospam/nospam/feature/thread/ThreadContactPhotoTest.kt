// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.Participant
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Written from the task spec, independently of ThreadViewModel's own
 * implementation: covers the new `contactPhotoUri` field on `ThreadUiState`
 * that travels alongside `contactName`. Setup mirrors ThreadViewModelTest
 * (same FakeTelephonyDataSource seam, same StandardTestDispatcher pattern
 * used there for the async contact-resolution tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadContactPhotoTest {

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

    @Test fun `a known sender with a photo populates both contact name and photo uri`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val telephony = telephonyWithThread9()
        telephony.contacts["+1555"] = Participant(
            address = "+1555", displayName = "Alice Freeman", photoUri = "content://x/1"
        )
        val vm = ThreadViewModel(telephony)

        vm.loadThread(9L)
        advanceUntilIdle()

        assertEquals("Alice Freeman", vm.uiState.value.contactName)
        assertEquals("content://x/1", vm.uiState.value.contactPhotoUri)
    }

    @Test fun `a known sender without a photo gives the name and a null photo uri`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val telephony = telephonyWithThread9()
        telephony.contacts["+1555"] = Participant(address = "+1555", displayName = "Alice Freeman")
        val vm = ThreadViewModel(telephony)

        vm.loadThread(9L)
        advanceUntilIdle()

        assertEquals("Alice Freeman", vm.uiState.value.contactName)
        assertNull(vm.uiState.value.contactPhotoUri)
    }

    @Test fun `an unknown address leaves both contact name and photo uri null`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val telephony = telephonyWithThread9()
        val vm = ThreadViewModel(telephony)

        vm.loadThread(9L)
        advanceUntilIdle()

        assertNull(vm.uiState.value.contactName)
        assertNull(vm.uiState.value.contactPhotoUri)
    }

    @Test fun `loading another thread first resets both contact name and photo uri to null`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val telephony = telephonyWithThread9()
        telephony.contacts["+1555"] = Participant(
            address = "+1555", displayName = "Alice Freeman", photoUri = "content://x/1"
        )
        val vm = ThreadViewModel(telephony)
        vm.loadThread(9L)
        advanceUntilIdle()
        assertEquals("Alice Freeman", vm.uiState.value.contactName)
        assertEquals("content://x/1", vm.uiState.value.contactPhotoUri)

        telephony.emitMessages(
            ThreadId(10),
            listOf(Message(MessageId(7), ThreadId(10), "+1999", "other", 5L, MessageType.INBOX, true)),
        )
        vm.loadThread(10L)
        advanceUntilIdle()

        assertNull(vm.uiState.value.contactName)
        assertNull(vm.uiState.value.contactPhotoUri)
    }
}

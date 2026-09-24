// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ThreadViewModel's `onMessageQueued` callback: invoked once each
 * time a message the user sent is handed to the radio successfully, never on
 * a failed send and never for a message the user received. Written from the
 * task spec independently of ThreadViewModel.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentSoundTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun incoming(id: Long, sim: Int? = 1) =
        Message(MessageId(id), ThreadId(9), "+15550009", "in $id", id, MessageType.INBOX, true, subscriptionId = sim)

    private fun failed(id: Long, sim: Int? = 1) =
        Message(MessageId(id), ThreadId(9), "+15550009", "again", id, MessageType.FAILED, true, subscriptionId = sim)

    private fun fake(vararg messages: Message) = FakeTelephonyDataSource().apply {
        subscriptions = listOf(TelephonyDataSource.SimInfo(1, "SIM 1"))
        emitMessages(ThreadId(9), messages.toList())
        nextThreadId = 9L
    }

    private fun TestScope.viewModelOn(fake: FakeTelephonyDataSource, onQueued: suspend () -> Unit): ThreadViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, onMessageQueued = onQueued)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        return vm
    }

    @Test fun `a successful send invokes onMessageQueued once`() = runTest {
        var queued = 0
        val fake = fake(incoming(1))
        val vm = viewModelOn(fake) { queued++ }

        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()

        assertEquals(1, fake.sentMessages.size)
        assertEquals(1, queued)
    }

    @Test fun `a failed send does not invoke onMessageQueued`() = runTest {
        var queued = 0
        val fake = fake(incoming(1)).apply { sendResult = Result.failure(RuntimeException("radio off")) }
        val vm = viewModelOn(fake) { queued++ }

        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()

        assertEquals(1, fake.sentMessages.size)
        assertEquals(0, queued)
    }

    @Test fun `two successful sends invoke onMessageQueued twice`() = runTest {
        var queued = 0
        val fake = fake(incoming(1))
        val vm = viewModelOn(fake) { queued++ }

        vm.onDraftChanged("first")
        vm.onSend()
        advanceUntilIdle()
        vm.onDraftChanged("second")
        vm.onSend()
        advanceUntilIdle()

        assertEquals(2, fake.sentMessages.size)
        assertEquals(2, queued)
    }

    @Test fun `receiving a message does not invoke onMessageQueued`() = runTest {
        var queued = 0
        val fake = fake(incoming(1))
        viewModelOn(fake) { queued++ }

        // A new incoming message arrives in the thread without the user sending anything.
        fake.emitMessages(ThreadId(9), listOf(incoming(1), incoming(2)))
        advanceUntilIdle()

        assertEquals(0, fake.sentMessages.size)
        assertEquals(0, queued)
    }

    @Test fun `a successful retry invokes onMessageQueued`() = runTest {
        var queued = 0
        val fake = fake(incoming(1), failed(7))
        val vm = viewModelOn(fake) { queued++ }

        vm.onRetry(7L)
        advanceUntilIdle()

        assertEquals(1, fake.sentMessages.size)
        assertEquals(1, queued)
    }

    @Test fun `a failed retry does not invoke onMessageQueued`() = runTest {
        var queued = 0
        val fake = fake(incoming(1), failed(7)).apply { sendResult = Result.failure(RuntimeException("no service")) }
        val vm = viewModelOn(fake) { queued++ }

        vm.onRetry(7L)
        advanceUntilIdle()

        assertEquals(1, fake.sentMessages.size)
        assertEquals(0, queued)
    }

    @Test fun `default onMessageQueued does nothing and does not throw`() = runTest {
        val fake = fake(incoming(1))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()

        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()

        assertEquals(1, fake.sentMessages.size)
    }
}

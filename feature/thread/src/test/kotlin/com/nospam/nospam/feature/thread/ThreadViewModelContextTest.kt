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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ThreadViewModel behaviour that needs an Android [Context]: the SIM list and the draft store. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadViewModelContextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun sim(id: Int) = TelephonyDataSource.SimInfo(id, "SIM $id")

    private fun incoming(id: Long, sim: Int?) =
        Message(MessageId(id), ThreadId(9), "+1555", "in", id, MessageType.INBOX, true, subscriptionId = sim)

    private fun fakeWith(vararg messages: Message) = FakeTelephonyDataSource().apply {
        subscriptions = listOf(sim(1), sim(2))
        emitMessages(ThreadId(9), messages.toList())
    }

    @Test fun `a reply defaults to the SIM the conversation last used`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fakeWith(incoming(1, sim = 2)))
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.selectedSimId)
    }

    @Test fun `a thread with no SIM history uses the first SIM, and the user's pick sticks`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(incoming(1, sim = null))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.selectedSimId)
        vm.onSimSelected(2)
        fake.emitMessages(ThreadId(9), listOf(incoming(5, sim = 1)))
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.selectedSimId)
    }

    @Test fun `sending clears the saved draft so the sent text does not come back`() = runBlocking {
        val fake = fakeWith(incoming(1, sim = 1))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        vm.onDraftChanged("about to send")
        awaitDraft { it == "about to send" }
        vm.onSend()
        awaitDraft { it == null }
        assertNull(DraftStore.load(context, 9L))
    }

    @Test fun `a failed send with no row to mark returns the text to the compose box`() = runTest {
        val fake = fakeWith(incoming(1, sim = 1))
        fake.writable = false
        fake.sendResult = Result.failure(IllegalStateException("radio off"))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L)
        vm.onDraftChanged("hello")
        vm.onSend()
        assertEquals("hello", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.messages.none { it.body == "hello" })
    }

    private suspend fun awaitDraft(until: (String?) -> Boolean) = withTimeout(5_000) {
        while (!until(DraftStore.load(context, 9L))) delay(20)
    }
}

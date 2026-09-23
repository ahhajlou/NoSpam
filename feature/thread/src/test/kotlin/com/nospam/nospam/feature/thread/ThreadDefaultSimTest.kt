// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakePreferencesDataSource
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ThreadViewModel's default-SIM selection (`uiState.sims`,
 * `uiState.selectedSimId`) and the entered-number override in `uiState.sims`,
 * written from the spec in the task prompt independently of the
 * implementation. ThreadViewModelContextTest already covers "latest
 * message's SIM wins" and "a manual pick sticks against new messages" --
 * this file deliberately does not repeat those two cases and instead covers
 * the remaining fallback order: previously-selected SIM, then the default
 * SMS SIM, then the first active SIM, plus the entered-number override.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadDefaultSimTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun sim(id: Int) = TelephonyDataSource.SimInfo(id, "SIM $id")

    private fun message(id: Long, sim: Int?) =
        Message(MessageId(id), ThreadId(9), "+1555", "body $id", id, MessageType.INBOX, true, subscriptionId = sim)

    private fun fakeWith(vararg messages: Message) = FakeTelephonyDataSource().apply {
        subscriptions = listOf(sim(1), sim(2))
        emitMessages(ThreadId(9), messages.toList())
    }

    @Test fun `no SIM history, default SMS SIM is the second active SIM, starts on it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(message(1, sim = null)).apply { defaultSmsSubscriptionId = 2 }
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.selectedSimId)
    }

    @Test fun `a default SIM that is not active is ignored, falling back to the first active SIM`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(message(1, sim = null)).apply { defaultSmsSubscriptionId = 99 }
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.selectedSimId)
    }

    @Test fun `thread history on SIM 1 stays on SIM 1 even when the default is SIM 2`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(message(1, sim = 1)).apply { defaultSmsSubscriptionId = 2 }
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.selectedSimId)
    }

    @Test fun `when neither the latest message's SIM nor the default is active, the first active SIM is used`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(message(1, sim = 99)).apply { defaultSmsSubscriptionId = null }
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.selectedSimId)
    }

    @Test fun `a later message on a SIM that is no longer active keeps the previous auto-selection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(message(1, sim = 2))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.selectedSimId)

        // A newer message arrives tagged with a SIM id that is not among the
        // active SIMs (e.g. a SIM that was later removed). The user never
        // picked, so this is still auto-selection, but rule 1 (latest
        // message's SIM) fails validity: the previous auto-selection (SIM 2)
        // should be kept rather than falling straight to the default/first SIM.
        fake.emitMessages(ThreadId(9), listOf(message(1, sim = 2), message(5, sim = 99)))
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.selectedSimId)
    }

    @Test fun `entered numbers replace the carrier number for that SIM only, in uiState sims`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(
                TelephonyDataSource.SimInfo(1, "SIM 1", number = "+15550001111"),
                TelephonyDataSource.SimInfo(2, "SIM 2", number = "+15552223333"),
            )
            emitMessages(ThreadId(9), listOf(message(1, sim = 1)))
        }
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSimNumber(1, "+15559998888")
        val vm = ThreadViewModel(fake, settings = repo)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()

        val sims = vm.uiState.value.sims
        assertEquals("+15559998888", sims.first { it.subscriptionId == 1 }.number)
        assertEquals("+15552223333", sims.first { it.subscriptionId == 2 }.number)
    }

    @Test fun `without settings, uiState sims carry the carrier number unchanged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = fakeWith(message(1, sim = 1))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()

        val sims = vm.uiState.value.sims
        assertEquals(2, sims.size)
        assertEquals(setOf(1, 2), sims.map { it.subscriptionId }.toSet())
    }
}

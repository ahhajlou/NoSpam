// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.SendOptions
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakePreferencesDataSource
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
 * Whether ThreadViewModel asks for a delivery report when it sends: only when
 * the SIM the message goes out on is enabled in
 * `SettingsRepository.deliveryReportSims`. Covers sends and retries. Written
 * from the spec independently of the implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryReportSendTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun incoming(id: Long, sim: Int?) =
        Message(MessageId(id), ThreadId(9), "+15550009", "in $id", id, MessageType.INBOX, true, subscriptionId = sim)

    private fun failed(id: Long, sim: Int?) =
        Message(MessageId(id), ThreadId(9), "+15550009", "again", id, MessageType.FAILED, true, subscriptionId = sim)

    private fun fake(sims: List<Int>, vararg messages: Message) = FakeTelephonyDataSource().apply {
        subscriptions = sims.map { TelephonyDataSource.SimInfo(it, "SIM $it") }
        emitMessages(ThreadId(9), messages.toList())
        nextThreadId = 9L
    }

    private suspend fun repoWith(vararg enabled: Int) = SettingsRepository(FakePreferencesDataSource()).apply {
        enabled.forEach { setDeliveryReports(it, true) }
    }

    private fun TestScope.sendOn(fake: FakeTelephonyDataSource, settings: SettingsRepository?): ThreadViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = settings)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()
        return vm
    }

    @Test fun `without settings no delivery report is asked for`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 1))
        sendOn(fake, settings = null)
        assertEquals(1, fake.sentMessages.size)
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }

    @Test fun `SIM enabled asks for a delivery report`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 1))
        sendOn(fake, repoWith(1))
        assertEquals(1, fake.sentMessages.single().third)
        assertEquals(listOf(SendOptions(deliveryReport = true)), fake.sentOptions)
    }

    @Test fun `only another SIM enabled does not ask for a delivery report`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 1))
        sendOn(fake, repoWith(2))
        assertEquals(1, fake.sentMessages.single().third)
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }

    @Test fun `nothing enabled does not ask for a delivery report`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 1))
        sendOn(fake, repoWith())
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }

    @Test fun `a SIM picked by hand decides, not the thread's SIM`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 1))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = repoWith(2))
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        vm.onSimSelected(2)
        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(2, fake.sentMessages.single().third)
        assertEquals(listOf(SendOptions(deliveryReport = true)), fake.sentOptions)
    }

    @Test fun `switching away from the enabled SIM stops asking`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 2))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = repoWith(2))
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        vm.onSimSelected(1)
        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(1, fake.sentMessages.single().third)
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }

    @Test fun `no SIM list means no SIM selected and no delivery report`() = runTest {
        val fake = fake(emptyList(), incoming(1, sim = 1))
        sendOn(fake, repoWith(1))
        assertEquals(1, fake.sentMessages.size)
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }

    @Test fun `a setting enabled after the thread opened applies to the next send`() = runTest {
        val fake = fake(listOf(1), incoming(1, sim = 1))
        val repo = repoWith()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = repo)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        repo.setDeliveryReports(1, true)
        advanceUntilIdle()
        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(listOf(SendOptions(deliveryReport = true)), fake.sentOptions)
    }

    // --- Retry ------------------------------------------------------------

    @Test fun `retrying on an enabled SIM asks for a delivery report`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 2), failed(7, sim = 2))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = repoWith(2))
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        vm.onRetry(7L)
        advanceUntilIdle()
        assertEquals(1, fake.sentMessages.size)
        assertEquals(2, fake.sentMessages.single().third)
        assertEquals(listOf(SendOptions(deliveryReport = true)), fake.sentOptions)
    }

    @Test fun `retrying on a SIM that is not enabled does not ask`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 2), failed(7, sim = 2))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = repoWith(1))
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        vm.onRetry(7L)
        advanceUntilIdle()
        assertEquals(1, fake.sentMessages.size)
        assertEquals(2, fake.sentMessages.single().third)
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }

    @Test fun `retrying without settings does not ask`() = runTest {
        val fake = fake(listOf(1, 2), incoming(1, sim = 2), failed(7, sim = 2))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ThreadViewModel(fake, settings = null)
        vm.loadThread(9L, context = context)
        advanceUntilIdle()
        vm.onRetry(7L)
        advanceUntilIdle()
        assertEquals(listOf(SendOptions(deliveryReport = false)), fake.sentOptions)
    }
}

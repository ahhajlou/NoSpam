// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written from the spec for opening a thread, without reading
 * [ThreadViewModel]: what is shown before and after the provider and the
 * contact lookup answer, when spam labels may appear, and replying to
 * alphanumeric sender IDs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelOpeningTest {

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.standardMain() = Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    private fun TestScope.unconfinedMain() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    private fun msg(id: Long, thread: Long, address: String = "+1555", body: String = "m$id", type: MessageType = MessageType.INBOX) =
        Message(MessageId(id), ThreadId(thread), address, body, id, type, true)

    // --- (a) a fresh view model ------------------------------------------------

    @Test fun `a fresh view model without a data source shows nothing and is loading`() = runTest {
        standardMain()
        val vm = ThreadViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.isLoading)
    }

    @Test fun `a fresh view model with a data source shows nothing and is loading`() = runTest {
        standardMain()
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9)))
        val vm = ThreadViewModel(fake, initialAddress = "+1555")
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.isLoading)
    }

    // --- (b) loading -----------------------------------------------------------

    @Test fun `messages arrive with the provider's first emission and loading ends`() = runTest {
        standardMain()
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9), msg(2, 9)))
        fake.emitMessages(ThreadId(10), listOf(msg(3, 10)))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L)
        // Nothing has run yet: the provider has not emitted.
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf(1L, 2L), vm.uiState.value.messages.map { it.id.value })
    }

    @Test fun `an empty thread stops loading too`() = runTest {
        standardMain()
        val fake = FakeTelephonyDataSource()
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test fun `an empty thread with no address stops loading and can still be replied to`() = runTest {
        unconfinedMain()
        val vm = ThreadViewModel(FakeTelephonyDataSource())
        vm.loadThread(9L)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertNull(vm.uiState.value.address)
        assertTrue(vm.uiState.value.canReply)
    }

    @Test fun `later provider emissions update messages`() = runTest {
        unconfinedMain()
        val fake = FakeTelephonyDataSource()
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L)
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9)))
        assertEquals(listOf(1L), vm.uiState.value.messages.map { it.id.value })
    }

    @Test fun `opening another thread does not show the previous thread's messages`() = runTest {
        standardMain()
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9)))
        fake.emitMessages(ThreadId(10), listOf(msg(3, 10, address = "+1666")))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L)
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.uiState.value.messages.map { it.id.value })

        vm.loadThread(10L)
        // Before thread 10's first emission: thread 9's messages are gone and it is loading again.
        assertTrue(vm.uiState.value.messages.none { it.threadId == ThreadId(9) })
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf(3L), vm.uiState.value.messages.map { it.id.value })
    }

    // --- (c) contact name and photo from the route ----------------------------

    @Test fun `route name and photo show immediately and a found contact replaces them`() = runTest {
        unconfinedMain()
        val fake = FakeTelephonyDataSource()
        val gate = CompletableDeferred<Unit>()
        fake.contactLookupGate = gate
        fake.contacts["+1555"] = Participant("+1555", displayName = "Ali Rezaei", photoUri = "content://photo/2")
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, address = "+1555", contactName = "Ali", contactPhotoUri = "content://photo/1")
        assertEquals("Ali", vm.uiState.value.contactName)
        assertEquals("content://photo/1", vm.uiState.value.contactPhotoUri)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Ali Rezaei", vm.uiState.value.contactName)
        assertEquals("content://photo/2", vm.uiState.value.contactPhotoUri)
    }

    @Test fun `route name is in state before any coroutine runs`() = runTest {
        standardMain()
        val vm = ThreadViewModel(FakeTelephonyDataSource())
        vm.loadThread(9L, address = "+1555", contactName = "Ali", contactPhotoUri = "content://photo/1")
        assertEquals("Ali", vm.uiState.value.contactName)
        assertEquals("content://photo/1", vm.uiState.value.contactPhotoUri)
    }

    @Test fun `route name stays when the lookup finds nothing`() = runTest {
        standardMain()
        val fake = FakeTelephonyDataSource()
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, address = "+1555", contactName = "Ali")
        advanceUntilIdle()
        assertEquals("Ali", vm.uiState.value.contactName)
    }

    // --- (d) spam labels wait for the contact lookup --------------------------

    private suspend fun flaggedRepo(state: ThreadSpamState = ThreadSpamState.SUSPECTED): SpamRepository {
        val db = NoSpamDatabase.inMemory()
        db.messageVerdictDao.insert(MessageVerdictEntity(1L, 9L, "+1555", isSpam = true, score = 0.9, createdAt = 1L))
        db.senderStateDao.upsert(SenderStateEntity("+1555", state, spamCount = 1))
        return SpamRepository(db, FakeSpamClassifier.alwaysHam())
    }

    private fun fakeWithThread9() = FakeTelephonyDataSource().apply {
        emitMessages(ThreadId(9), listOf(msg(1, 9), msg(2, 9)))
    }

    @Test fun `labels are held while the lookup is pending and appear once it says not a contact`() = runTest {
        standardMain()
        val fake = fakeWithThread9()
        val gate = CompletableDeferred<Unit>()
        fake.contactLookupGate = gate
        val vm = ThreadViewModel(fake, spamRepository = flaggedRepo())
        vm.loadThread(9L, address = "+1555")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.spamMessageIds.isEmpty())
        assertNull(vm.uiState.value.spamBanner)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(1L), vm.uiState.value.spamMessageIds)
        assertEquals(SpamBanner.SUSPECTED_MESSAGES, vm.uiState.value.spamBanner)
    }

    @Test fun `labels are held while pending even when the address comes from the messages`() = runTest {
        standardMain()
        val fake = fakeWithThread9()
        val gate = CompletableDeferred<Unit>()
        fake.contactLookupGate = gate
        val vm = ThreadViewModel(fake, spamRepository = flaggedRepo())
        vm.loadThread(9L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.spamMessageIds.isEmpty())
        assertNull(vm.uiState.value.spamBanner)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(1L), vm.uiState.value.spamMessageIds)
    }

    @Test fun `a sender in spam gets the in-spam banner once the lookup answers`() = runTest {
        standardMain()
        val fake = fakeWithThread9()
        val gate = CompletableDeferred<Unit>()
        fake.contactLookupGate = gate
        val vm = ThreadViewModel(fake, spamRepository = flaggedRepo(ThreadSpamState.SPAM))
        vm.loadThread(9L, address = "+1555")
        advanceUntilIdle()
        assertNull(vm.uiState.value.spamBanner)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SpamBanner.IN_SPAM, vm.uiState.value.spamBanner)
    }

    @Test fun `no labels ever when the lookup finds a contact`() = runTest {
        standardMain()
        val fake = fakeWithThread9()
        val gate = CompletableDeferred<Unit>()
        fake.contactLookupGate = gate
        fake.contacts["+1555"] = Participant("+1555", displayName = "Ali")
        val vm = ThreadViewModel(fake, spamRepository = flaggedRepo())
        vm.loadThread(9L, address = "+1555")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.spamMessageIds.isEmpty())
        assertNull(vm.uiState.value.spamBanner)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.spamMessageIds.isEmpty())
        assertNull(vm.uiState.value.spamBanner)
    }

    @Test fun `no labels ever when the route carried a contact name`() = runTest {
        standardMain()
        val fake = fakeWithThread9() // lookup answers "not a contact"
        val vm = ThreadViewModel(fake, spamRepository = flaggedRepo())
        vm.loadThread(9L, address = "+1555", contactName = "Ali")
        assertTrue(vm.uiState.value.spamMessageIds.isEmpty())
        assertNull(vm.uiState.value.spamBanner)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.spamMessageIds.isEmpty())
        assertNull(vm.uiState.value.spamBanner)
    }

    // --- (e) canReply ----------------------------------------------------------

    private fun canReplyFor(address: String?): Boolean {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = ThreadViewModel(FakeTelephonyDataSource())
        vm.loadThread(9L, address = address)
        return vm.uiState.value.canReply
    }

    @Test fun `cannot reply to alphanumeric sender ids`() {
        assertFalse(canReplyFor("MCI"))
        assertFalse(canReplyFor("Snapp"))
        assertFalse(canReplyFor("همراه اول"))
    }

    @Test fun `can reply to phone numbers and numeric short codes`() {
        assertTrue(canReplyFor("+15551234567"))
        assertTrue(canReplyFor("09121234567"))
        assertTrue(canReplyFor("3000123"))
    }

    @Test fun `can reply while the address is unknown`() {
        assertTrue(canReplyFor(null))
        Dispatchers.setMain(Dispatchers.Unconfined)
        assertTrue(ThreadViewModel().uiState.value.canReply)
    }

    @Test fun `cannot reply once the messages reveal an alphanumeric sender`() = runTest {
        unconfinedMain()
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9, address = "MCI")))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L)
        assertFalse(vm.uiState.value.canReply)
    }

    // --- (f) sending -----------------------------------------------------------

    @Test fun `sending to an alphanumeric sender does nothing and keeps the draft`() = runTest {
        unconfinedMain()
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9, address = "MCI")))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, address = "MCI")
        vm.onDraftChanged("hello")
        vm.onSend()
        advanceUntilIdle()
        assertTrue(fake.insertedOutbox.isEmpty())
        assertTrue(fake.sentMessages.isEmpty())
        assertEquals("hello", vm.uiState.value.draft)
    }

    @Test fun `sending to a numeric short code works`() = runTest {
        standardMain()
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), listOf(msg(1, 9, address = "3000123")))
        val vm = ThreadViewModel(fake)
        vm.loadThread(9L, address = "3000123")
        advanceUntilIdle()
        vm.onDraftChanged("STOP")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(listOf("3000123" to "STOP"), fake.insertedOutbox)
        assertEquals(listOf("3000123"), fake.sentMessages.map { it.first })
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import com.nospam.nospam.core.data.BlocklistRepository
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * SendersViewModel: the blocked and "Not spam" lists, their contact
 * enrichment, and undoing a rule.
 *
 * Written from the feature spec independently of the implementation: the view
 * model's and repositories' bodies were not read.
 */
class SendersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Env(
        val db: NoSpamDatabase = NoSpamDatabase.inMemory(),
        val tele: FakeTelephonyDataSource = FakeTelephonyDataSource(),
    ) {
        val blocklist = BlocklistRepository(db, telephony = tele)
        val spam = SpamRepository(db, FakeSpamClassifier.alwaysHam())
    }

    /**
     * advanceUntilIdle, then — in case a repository hops to a real dispatcher
     * (e.g. IO for the system block list) — keep advancing in real time until
     * [done] holds or five seconds pass. Assertions follow separately.
     */
    private fun settle(vm: SendersViewModel, done: (SendersUiState) -> Boolean = { it.blocked != null && it.allowed != null }) {
        val deadline = System.currentTimeMillis() + 5_000
        dispatcher.scheduler.advanceUntilIdle()
        while (!done(vm.uiState.value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    private suspend fun NoSpamDatabase.allow(address: String, updatedAt: Long) =
        senderStateDao.upsert(SenderStateEntity(address, ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = updatedAt))

    @Test fun `with no arguments both lists are empty once loaded`() = runTest {
        val vm = SendersViewModel()
        settle(vm)
        assertEquals(emptyList<SenderRule>(), vm.uiState.value.blocked)
        assertEquals(emptyList<SenderRule>(), vm.uiState.value.allowed)
    }

    @Test fun `a missing blocklist repository shows blocked as empty, allowed still loads`() = runTest {
        val env = Env()
        env.db.allow("+15550001", 1L)
        val vm = SendersViewModel(spam = env.spam, telephony = env.tele)
        settle(vm)
        assertEquals(emptyList<SenderRule>(), vm.uiState.value.blocked)
        assertEquals(listOf(SenderRule("+15550001")), vm.uiState.value.allowed)
    }

    @Test fun `a missing spam repository shows allowed as empty, blocked still loads`() = runTest {
        val env = Env()
        env.blocklist.block("+15550001")
        val vm = SendersViewModel(blocklist = env.blocklist, telephony = env.tele)
        settle(vm)
        assertEquals(listOf(SenderRule("+15550001")), vm.uiState.value.blocked)
        assertEquals(emptyList<SenderRule>(), vm.uiState.value.allowed)
    }

    @Test fun `blocked mirrors the repository in order, system numbers included`() = runTest {
        val env = Env()
        env.tele.systemBlocked += "+15559001"
        env.db.blocklistDao.insert(BlocklistEntity(address = "+15550001", createdAt = 1_000L))
        env.db.blocklistDao.insert(BlocklistEntity(address = "NSTEST_A", createdAt = 2_000L))
        val expected = env.blocklist.observeBlockedSenders().first()

        val vm = SendersViewModel(env.blocklist, env.spam, env.tele)
        settle(vm)

        assertEquals(expected, vm.uiState.value.blocked!!.map { it.address })
        assertEquals(setOf("+15550001", "NSTEST_A", "+15559001"), expected.toSet())
    }

    @Test fun `allowed mirrors the repository, most recently updated first`() = runTest {
        val env = Env()
        env.db.allow("+15550001", 100L)
        env.db.allow("+15550002", 300L)
        env.db.allow("NSTEST_A", 200L)

        val vm = SendersViewModel(env.blocklist, env.spam, env.tele)
        settle(vm)

        assertEquals(listOf("+15550002", "NSTEST_A", "+15550001"), vm.uiState.value.allowed!!.map { it.address })
    }

    @Test fun `rules are enriched with the contact's name and photo when known`() = runTest {
        val env = Env()
        env.tele.contacts["+15550001"] = Participant("+15550001", displayName = "Ada", photoUri = "content://photo/1")
        env.tele.contacts["+15550002"] = Participant("+15550002", displayName = "Bob")
        env.blocklist.block("+15550001")
        env.blocklist.block("NSTEST_A")
        env.db.allow("+15550002", 1L)
        env.db.allow("+15550003", 2L)

        val vm = SendersViewModel(env.blocklist, env.spam, env.tele)
        settle(vm)

        val blocked = vm.uiState.value.blocked!!.associateBy { it.address }
        assertEquals(SenderRule("+15550001", "Ada", "content://photo/1"), blocked["+15550001"])
        assertEquals(SenderRule("NSTEST_A", null, null), blocked["NSTEST_A"])
        assertEquals(
            listOf(SenderRule("+15550003"), SenderRule("+15550002", "Bob", null)),
            vm.uiState.value.allowed,
        )
    }

    @Test fun `a contact lookup that throws leaves the rule without a name`() = runTest {
        val env = Env()
        env.blocklist.block("+15550001")
        env.db.allow("+15550002", 1L)
        val throwing = object : TelephonyDataSource by env.tele {
            override suspend fun lookupContact(address: String): Participant? =
                throw SecurityException("no contacts permission")
        }

        val vm = SendersViewModel(env.blocklist, env.spam, throwing)
        settle(vm)

        assertEquals(listOf(SenderRule("+15550001")), vm.uiState.value.blocked)
        assertEquals(listOf(SenderRule("+15550002")), vm.uiState.value.allowed)
    }

    @Test fun `without a telephony source rules carry no contact details`() = runTest {
        val env = Env()
        env.blocklist.block("+15550001")
        val vm = SendersViewModel(env.blocklist, env.spam)
        settle(vm)
        assertEquals(listOf(SenderRule("+15550001")), vm.uiState.value.blocked)
    }

    @Test fun `the lists follow rule changes made elsewhere`() = runTest {
        val env = Env()
        val vm = SendersViewModel(env.blocklist, env.spam, env.tele)
        settle(vm)
        assertEquals(emptyList<SenderRule>(), vm.uiState.value.blocked)
        assertEquals(emptyList<SenderRule>(), vm.uiState.value.allowed)

        env.blocklist.block("+15550001")
        env.spam.markSenderNotSpam(ThreadId(9), "+15550002")
        settle(vm) { it.blocked?.size == 1 && it.allowed?.size == 1 }

        assertEquals(listOf("+15550001"), vm.uiState.value.blocked!!.map { it.address })
        assertEquals(listOf("+15550002"), vm.uiState.value.allowed!!.map { it.address })
    }

    @Test fun `unblock removes the sender from blocked and from the blocklist`() = runTest {
        val env = Env()
        env.blocklist.block("+15550001")
        env.blocklist.block("+15550002")
        val vm = SendersViewModel(env.blocklist, env.spam, env.tele)
        settle(vm) { it.blocked?.size == 2 }

        vm.unblock("+15550001")
        settle(vm) { it.blocked?.size == 1 }

        assertEquals(listOf("+15550002"), vm.uiState.value.blocked!!.map { it.address })
        assertEquals(false, env.blocklist.isBlocked("+15550001"))
        assertEquals(true, env.blocklist.isBlocked("+15550002"))
    }

    @Test fun `removeAllow removes the sender from allowed and undoes the override`() = runTest {
        val env = Env()
        env.db.senderStateDao.upsert(
            SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, spamCount = 2, isUserOverride = true, updatedAt = 1L)
        )
        env.db.allow("+15550002", 2L)
        val vm = SendersViewModel(env.blocklist, env.spam, env.tele)
        settle(vm)

        vm.removeAllow("+15550001")
        settle(vm) { it.allowed?.size == 1 }

        assertEquals(listOf("+15550002"), vm.uiState.value.allowed!!.map { it.address })
        assertEquals(ThreadSpamState.MIXED, env.db.senderStateDao.getByAddress("+15550001")?.state)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bulk [BlocklistRepository.block] / [BlocklistRepository.unblock]: the same
 * effect as the single-address form applied once per distinct address.
 *
 * Written from the spec independently of the implementation, which was not read
 * (only its public signatures were). Most checks are differential: one database
 * driven by the bulk call, another by the single calls, compared field by field.
 */
class BlocklistRepositoryBulkTest {

    private val a = "+98911"
    private val b = "+98912"
    private val c = "+98913"
    private val bystander = "+98919"

    private fun conversations() = listOf(a, b, c, bystander).mapIndexed { i, addr ->
        Conversation(ThreadId(i + 1L), listOf(Participant(addr)), "m", i + 1L, 1, true)
    }

    private class Env(val db: NoSpamDatabase, val repo: BlocklistRepository)

    private suspend fun env(): Env {
        val db = NoSpamDatabase.inMemory()
        (1L..4L).forEach { db.pinnedDao.pin(it) }
        return Env(db, BlocklistRepository(db, telephony = FakeTelephonyDataSource(conversations())))
    }

    private suspend fun NoSpamDatabase.snapshot() = Triple(
        blocklistDao.observeAll().first().map { it.address }.sorted(),
        senderStateDao.getAll().map { Triple(it.normalizedAddress, it.state, listOf(it.spamCount, it.hamCount, if (it.isUserOverride) 1 else 0)) }
            .sortedBy { it.first },
        pinnedDao.observeAll().first().map { it.threadId }.toSet(),
    )

    @Test fun `bulk block matches single blocks and unpins each sender's conversation`() = runTest {
        val bulk = env()
        val single = env()

        bulk.repo.block(listOf(a, b, c))
        listOf(a, b, c).forEach { single.repo.block(it) }

        val rows = bulk.db.blocklistDao.observeAll().first()
        assertEquals(3, rows.size)
        listOf(a, b, c).forEach { assertTrue("$it blocked", bulk.repo.isBlocked(it)) }
        assertFalse(bulk.repo.isBlocked(bystander))
        assertEquals(setOf(4L), bulk.db.pinnedDao.observeAll().first().map { it.threadId }.toSet())
        assertEquals(single.db.snapshot(), bulk.db.snapshot())
    }

    @Test fun `duplicate addresses in the input are processed once`() = runTest {
        val e = env()
        e.repo.block(listOf(a, b, a, a, b))
        val rows = e.db.blocklistDao.observeAll().first().map { it.address }
        assertEquals(rows.toString(), 2, rows.size)
        assertEquals(setOf(a, b), rows.toSet())
    }

    @Test fun `bulk unblock removes every row and matches single unblocks`() = runTest {
        val bulk = env()
        val single = env()
        for (e in listOf(bulk, single)) {
            e.repo.block(listOf(a, b, c))
            // What ingress writes for blocked senders, as in BlocklistRepositoryTest.
            e.db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.BLOCKED, spamCount = 2, isUserOverride = true))
            e.db.senderStateDao.upsert(SenderStateEntity(b, ThreadSpamState.BLOCKED, spamCount = 0, hamCount = 4, isUserOverride = true))
        }

        bulk.repo.unblock(listOf(a, b, c, a))
        listOf(a, b, c).forEach { single.repo.unblock(it) }

        assertTrue(bulk.db.blocklistDao.observeAll().first().isEmpty())
        listOf(a, b, c).forEach { assertFalse("$it unblocked", bulk.repo.isBlocked(it)) }
        assertEquals(ThreadSpamState.MIXED, bulk.db.senderStateDao.getByAddress(a)?.state)
        assertEquals(ThreadSpamState.CLEAN, bulk.db.senderStateDao.getByAddress(b)?.state)
        assertEquals(single.db.snapshot(), bulk.db.snapshot())
    }

    @Test fun `bulk unblock leaves other blocked addresses alone`() = runTest {
        val e = env()
        e.repo.block(listOf(a, b, c))
        e.repo.unblock(listOf(a, c))
        assertEquals(listOf(b), e.db.blocklistDao.observeAll().first().map { it.address })
    }

    @Test fun `empty collections do nothing`() = runTest {
        val e = env()
        e.repo.block(listOf(a))
        val before = e.db.snapshot()
        e.repo.block(emptyList<String>())
        e.repo.unblock(emptyList<String>())
        assertEquals(before, e.db.snapshot())
    }
}

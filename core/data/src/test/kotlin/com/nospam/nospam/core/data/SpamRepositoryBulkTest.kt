// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Report spam" / "Not spam" on a selection: [SpamRepository.markSendersSpam] and
 * [SpamRepository.markSendersNotSpam].
 *
 * Written from the spec independently of the implementation, which was not read
 * (only its public signatures were). Addresses are already in E.164 form, which
 * is the normalized key without a Context (as in ConversationsRepositoryTest).
 */
class SpamRepositoryBulkTest {

    private val a = "+989121111111"
    private val b = "+989122222222"

    private fun repo(db: NoSpamDatabase) = SpamRepository(db, FakeSpamClassifier.alwaysSpam())

    private suspend fun NoSpamDatabase.pinnedIds() = pinnedDao.observeAll().first().map { it.threadId }.toSet()

    // --- Report spam -------------------------------------------------------------

    @Test fun `markSendersSpam unpins every thread and writes an override verdict per thread`() = runTest {
        val db = NoSpamDatabase.inMemory()
        listOf(1L, 2L, 3L, 4L).forEach { db.pinnedDao.pin(it) }

        repo(db).markSendersSpam(listOf(ThreadId(1) to a, ThreadId(2) to a, ThreadId(3) to b))

        assertEquals(setOf(4L), db.pinnedIds())
        for (t in 1L..3L) {
            val v = db.spamVerdictDao.getByThread(t)
            assertTrue("verdict $t is spam", v!!.isSpam)
            assertTrue("verdict $t is a user override", v.isUserOverride)
        }
        assertNull(db.spamVerdictDao.getByThread(4))
    }

    @Test fun `markSendersSpam writes one SPAM override sender state per distinct address`() = runTest {
        val db = NoSpamDatabase.inMemory()

        repo(db).markSendersSpam(listOf(ThreadId(1) to a, ThreadId(2) to a, ThreadId(3) to b))

        val states = db.senderStateDao.getAll()
        assertEquals(states.toString(), setOf(a, b), states.map { it.normalizedAddress }.toSet())
        assertEquals(2, states.size)
        states.forEach {
            assertEquals(ThreadSpamState.SPAM, it.state)
            assertTrue(it.isUserOverride)
        }
    }

    /**
     * Two raw forms of one alphanumeric sender id normalize to one key (CLAUDE.md
     * §4: fallback is the trimmed, upper-cased raw value), so one sender_state row.
     */
    @Test fun `markSendersSpam dedupes addresses after normalization`() = runTest {
        val db = NoSpamDatabase.inMemory()

        repo(db).markSendersSpam(listOf(ThreadId(1) to "BankMellat", ThreadId(2) to " BANKMELLAT "))

        val states = db.senderStateDao.getAll()
        assertEquals(states.toString(), 1, states.size)
        assertEquals(ThreadSpamState.SPAM, states.single().state)
    }

    @Test fun `markSendersSpam on an empty selection writes nothing`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.pinnedDao.pin(1)

        repo(db).markSendersSpam(emptyList())

        assertEquals(setOf(1L), db.pinnedIds())
        assertTrue(db.senderStateDao.getAll().isEmpty())
        assertTrue(db.spamVerdictDao.observeSpam().first().isEmpty())
        assertNull(db.spamVerdictDao.getByThread(1))
    }

    @Test fun `markSenderSpam for one thread matches the bulk form`() = runTest {
        val single = NoSpamDatabase.inMemory().also { it.pinnedDao.pin(7) }
        val bulk = NoSpamDatabase.inMemory().also { it.pinnedDao.pin(7) }

        repo(single).markSenderSpam(ThreadId(7), a)
        repo(bulk).markSendersSpam(listOf(ThreadId(7) to a))

        for (db in listOf(single, bulk)) {
            assertFalse(db.pinnedDao.isPinned(7))
            val v = db.spamVerdictDao.getByThread(7)!!
            assertTrue(v.isSpam && v.isUserOverride)
            val s = db.senderStateDao.getByAddress(a)!!
            assertEquals(ThreadSpamState.SPAM, s.state)
            assertTrue(s.isUserOverride)
            assertEquals(1, db.senderStateDao.getAll().size)
        }
    }

    // --- Not spam ----------------------------------------------------------------

    @Test fun `markSendersNotSpam flips existing verdicts and creates missing ones`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 0.97))

        repo(db).markSendersNotSpam(listOf(ThreadId(1) to a, ThreadId(2) to b))

        for (t in listOf(1L, 2L)) {
            val v = db.spamVerdictDao.getByThread(t)
            assertFalse("verdict $t is not spam", v!!.isSpam)
            assertTrue("verdict $t is a user override", v.isUserOverride)
        }
        assertTrue(db.spamVerdictDao.observeSpam().first().isEmpty())
    }

    @Test fun `markSendersNotSpam writes one TRUSTED override sender state per distinct address`() = runTest {
        val db = NoSpamDatabase.inMemory()
        repo(db).markSendersSpam(listOf(ThreadId(1) to a, ThreadId(2) to a, ThreadId(3) to b))

        repo(db).markSendersNotSpam(listOf(ThreadId(1) to a, ThreadId(2) to a, ThreadId(3) to b))

        val states = db.senderStateDao.getAll()
        assertEquals(states.toString(), 2, states.size)
        assertEquals(setOf(a, b), states.map { it.normalizedAddress }.toSet())
        states.forEach {
            assertEquals(ThreadSpamState.TRUSTED, it.state)
            assertTrue(it.isUserOverride)
        }
    }

    @Test fun `markSendersNotSpam leaves pins alone`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.pinnedDao.pin(1)
        db.pinnedDao.pin(3)

        repo(db).markSendersNotSpam(listOf(ThreadId(1) to a, ThreadId(2) to b))

        assertEquals(setOf(1L, 3L), db.pinnedIds())
    }

    @Test fun `markSendersNotSpam on an empty selection writes nothing`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 0.9))

        repo(db).markSendersNotSpam(emptyList())

        assertTrue(db.senderStateDao.getAll().isEmpty())
        assertTrue(db.spamVerdictDao.getByThread(1)!!.isSpam)
        assertNull(db.spamVerdictDao.getByThread(2))
    }
}

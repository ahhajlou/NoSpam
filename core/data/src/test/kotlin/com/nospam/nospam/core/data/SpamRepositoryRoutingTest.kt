// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's spam decisions under the routing model (TODO.md, "Spam routing —
 * agreed model"): sender-wide decisions keep the sender's evidence, per-message
 * corrections move it, and a reply clears automatic spam but never a decision.
 */
class SpamRepositoryRoutingTest {

    private val a = "+989121111111"

    private fun repo(db: NoSpamDatabase) = SpamRepository(db, FakeSpamClassifier.alwaysSpam())

    private suspend fun NoSpamDatabase.state(address: String = a) = senderStateDao.getByAddress(address)!!

    // --- Sender-wide decisions keep the counts ------------------------------------

    @Test fun `Not spam keeps the sender's counts and reply flag`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.MIXED, spamCount = 3, hamCount = 2, hasReplied = true))

        repo(db).markSendersNotSpam(listOf(ThreadId(1) to a))

        val s = db.state()
        assertEquals(ThreadSpamState.TRUSTED, s.state)
        assertTrue(s.isUserOverride)
        assertEquals(3, s.spamCount)
        assertEquals(2, s.hamCount)
        assertTrue(s.hasReplied)
    }

    @Test fun `Report spam keeps the sender's counts`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.MIXED, spamCount = 1, hamCount = 5))

        repo(db).markSendersSpam(listOf(ThreadId(1) to a))

        val s = db.state()
        assertEquals(ThreadSpamState.SPAM, s.state)
        assertTrue(s.isUserOverride)
        assertEquals(1, s.spamCount)
        assertEquals(5, s.hamCount)
    }

    @Test fun `undoing Not spam after it can return the sender to MIXED`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.MIXED, spamCount = 2, hamCount = 1))
        val repo = repo(db)

        repo.markSendersNotSpam(listOf(ThreadId(1) to a))
        repo.removeAllow(a)

        assertEquals(ThreadSpamState.MIXED, db.state().state)
        assertFalse(db.state().isUserOverride)
    }

    // --- Per-message corrections --------------------------------------------------

    @Test fun `Not spam on a sender's only spam message returns it to CLEAN`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.MIXED, spamCount = 1, hamCount = 2))
        db.messageVerdictDao.insert(MessageVerdictEntity(10, 1, a, isSpam = true, score = 2.0))

        repo(db).markMessageNotSpam(10)

        val s = db.state()
        assertEquals(ThreadSpamState.CLEAN, s.state)
        assertEquals(0, s.spamCount)
        assertEquals(3, s.hamCount)
        assertEquals(false, db.messageVerdictDao.getByMessageId(10)!!.userLabel)
    }

    @Test fun `Not spam on a SPAM sender's message brings it back to the inbox`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.SPAM, spamCount = 3))
        db.messageVerdictDao.insert(MessageVerdictEntity(10, 1, a, isSpam = true, score = 2.0))

        repo(db).markMessageNotSpam(10)

        assertEquals(ThreadSpamState.MIXED, db.state().state)
    }

    @Test fun `Not spam twice on the same message counts once`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.MIXED, spamCount = 2, hamCount = 1))
        db.messageVerdictDao.insert(MessageVerdictEntity(10, 1, a, isSpam = true, score = 2.0))
        val repo = repo(db)

        repo.markMessageNotSpam(10)
        repo.markMessageNotSpam(10)

        assertEquals(1, db.state().spamCount)
        assertEquals(2, db.state().hamCount)
    }

    @Test fun `Report spam on one message adds evidence but never hides the conversation`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.CLEAN, hamCount = 1))
        db.messageVerdictDao.insert(MessageVerdictEntity(10, 1, a, isSpam = false, score = -1.0))

        repo(db).markMessageSpam(10)

        val s = db.state()
        assertEquals(ThreadSpamState.MIXED, s.state)
        assertEquals(1, s.spamCount)
        assertEquals(1, s.hamCount)
    }

    @Test fun `a per-message correction leaves a user decision alone`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val trusted = SenderStateEntity(a, ThreadSpamState.TRUSTED, spamCount = 2, isUserOverride = true, updatedAt = 1L)
        db.senderStateDao.upsert(trusted)
        db.messageVerdictDao.insert(MessageVerdictEntity(10, 1, a, isSpam = true, score = 2.0))

        repo(db).markMessageNotSpam(10)

        assertEquals(trusted, db.state())
        assertEquals(false, db.messageVerdictDao.getByMessageId(10)!!.userLabel)
    }

    // --- Replies --------------------------------------------------------------------

    @Test fun `a reply brings an automatic SPAM sender back to the inbox and is remembered`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.SPAM, spamCount = 4))

        repo(db).recordReply(a)

        val s = db.state()
        assertEquals(ThreadSpamState.MIXED, s.state)
        assertTrue(s.hasReplied)
        assertEquals(4, s.spamCount)
    }

    @Test fun `a reply to a new sender records the reply`() = runTest {
        val db = NoSpamDatabase.inMemory()

        repo(db).recordReply(a)

        assertEquals(ThreadSpamState.CLEAN, db.state().state)
        assertTrue(db.state().hasReplied)
    }

    @Test fun `a reply never undoes the user's Report spam`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.SPAM, spamCount = 1, isUserOverride = true))

        repo(db).recordReply(a)

        val s = db.state()
        assertEquals(ThreadSpamState.SPAM, s.state)
        assertTrue(s.isUserOverride)
    }

    @Test fun `a reply never unblocks`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity(a, ThreadSpamState.BLOCKED, spamCount = 2))

        repo(db).recordReply(a)

        assertEquals(ThreadSpamState.BLOCKED, db.state().state)
    }
}

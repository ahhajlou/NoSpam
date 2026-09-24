// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.Event
import app.cash.turbine.test
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bulk (multi-selection) operations on [ConversationsRepository]: one repository
 * call per selection instead of a UI loop.
 *
 * Written from the spec independently of the implementation, which was not read
 * (only its public signatures were).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsRepositoryBulkTest {

    private fun conv(id: Long, address: String = "+9891200000$id", date: Long = id) =
        Conversation(ThreadId(id), listOf(Participant(address)), "hi $id", date, 1, true)

    private fun ids(vararg v: Long) = v.map { ThreadId(it) }

    private class Env(val tele: FakeTelephonyDataSource, val db: NoSpamDatabase, val repo: ConversationsRepository)

    private fun TestScope.env(count: Int = 5): Env {
        val tele = FakeTelephonyDataSource((1L..count).map { conv(it) })
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(tele, db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        return Env(tele, db, repo)
    }

    private suspend fun NoSpamDatabase.archivedIds() = archivedDao.observeAll().first().map { it.threadId }.toSet()
    private suspend fun NoSpamDatabase.pinnedIds() = pinnedDao.observeAll().first().map { it.threadId }.toSet()
    private suspend fun NoSpamDatabase.starredIds() = starredDao.observeAll().first().map { it.threadId }.toSet()
    private suspend fun NoSpamDatabase.mutedIds() = mutedDao.observeAll().first().map { it.threadId }.toSet()

    // --- setRead -----------------------------------------------------------------

    @Test fun `setRead on a selection is one telephony call carrying every thread`() = runTest {
        val e = env()
        e.repo.setRead(ids(1, 2, 3), true)
        assertEquals(1, e.tele.setThreadsReadCalls.size)
        val (called, read) = e.tele.setThreadsReadCalls.single()
        assertEquals(setOf(1L, 2L, 3L), called.toSet())
        assertEquals(3, called.size)
        assertTrue(read)

        e.repo.setRead(ids(4, 5), false)
        assertEquals(2, e.tele.setThreadsReadCalls.size)
        assertEquals(setOf(4L, 5L) to false, e.tele.setThreadsReadCalls[1].let { it.first.toSet() to it.second })
    }

    @Test fun `setRead on an empty selection makes no telephony call`() = runTest {
        val e = env()
        e.repo.setRead(emptyList(), true)
        e.repo.setRead(emptyList(), false)
        assertTrue(e.tele.setThreadsReadCalls.isEmpty())
        assertTrue(e.tele.markedReadThreadIds.isEmpty())
    }

    // --- archive / unarchive -----------------------------------------------------

    @Test fun `archive on a selection archives and unpins every thread and nothing else`() = runTest {
        val e = env()
        e.repo.setPin(ids(1, 2, 5), true)
        e.repo.archive(ids(1, 2, 3))
        assertEquals(setOf(1L, 2L, 3L), e.db.archivedIds())
        assertEquals(setOf(5L), e.db.pinnedIds())
        assertEquals(setOf(4L, 5L), e.repo.observeConversations().first().map { it.threadId.value }.toSet())
        assertEquals(setOf(1L, 2L, 3L), e.repo.observeArchived().first().map { it.threadId.value }.toSet())
    }

    @Test fun `unarchive on a selection unarchives every thread without restoring pins`() = runTest {
        val e = env()
        e.repo.setPin(ids(1, 2), true)
        e.repo.archive(ids(1, 2, 3))
        e.repo.unarchive(ids(1, 2))
        assertEquals(setOf(3L), e.db.archivedIds())
        assertTrue(e.db.pinnedIds().isEmpty())
        val inbox = e.repo.observeConversations().first()
        assertEquals(setOf(1L, 2L, 4L, 5L), inbox.map { it.threadId.value }.toSet())
        assertTrue(inbox.none { it.isPinned })
    }

    @Test fun `archive and unarchive on an empty selection change nothing`() = runTest {
        val e = env()
        e.repo.setPin(ids(1), true)
        e.repo.archive(ids(2))
        e.repo.archive(emptyList())
        e.repo.unarchive(emptyList())
        assertEquals(setOf(2L), e.db.archivedIds())
        assertEquals(setOf(1L), e.db.pinnedIds())
    }

    /**
     * The inbox must not pass through a state where only part of the selection is
     * archived. The in-memory DAOs publish synchronously, so the collector sees
     * every intermediate state deterministically. At most one new emission per
     * flag table touched (archived, pinned).
     */
    @Test fun `archiving a selection never shows a partially archived inbox`() = runTest {
        val e = env(count = 8)
        e.repo.setPin(ids(2, 4), true)
        val selection = (1L..6L).toList()
        val before = (1L..8L).toSet()
        val after = setOf(7L, 8L)
        e.repo.observeConversations().test {
            assertEquals(before, awaitItem().map { it.threadId.value }.toSet())
            e.repo.archive(selection.map { ThreadId(it) })
            testScheduler.advanceUntilIdle()
            val states = cancelAndConsumeRemainingEvents()
                .filterIsInstance<Event.Item<List<Conversation>>>()
                .map { ev -> ev.value.map { it.threadId.value }.toSet() }
            assertTrue("no emission after archive", states.isNotEmpty())
            states.forEach { s ->
                assertTrue("intermediate inbox state $s", s == before || s == after)
            }
            assertEquals(after, states.last())
            assertTrue("expected at most 2 emissions (archived + pinned), got ${states.size}: $states", states.size <= 2)
        }
    }

    // --- star / pin / mute -------------------------------------------------------

    @Test fun `setStar setPin setMute on a selection set and clear the flag for all`() = runTest {
        val e = env()
        e.repo.setStar(ids(1, 2, 3), true)
        e.repo.setPin(ids(2, 3, 4), true)
        e.repo.setMute(ids(3, 4, 5), true)
        assertEquals(setOf(1L, 2L, 3L), e.db.starredIds())
        assertEquals(setOf(2L, 3L, 4L), e.db.pinnedIds())
        assertEquals(setOf(3L, 4L, 5L), e.db.mutedIds())

        // Setting again is idempotent.
        e.repo.setStar(ids(1, 2), true)
        assertEquals(setOf(1L, 2L, 3L), e.db.starredIds())

        e.repo.setStar(ids(1, 3, 5), false)
        e.repo.setPin(ids(2, 4, 1), false)
        e.repo.setMute(ids(3, 5), false)
        assertEquals(setOf(2L), e.db.starredIds())
        assertEquals(setOf(3L), e.db.pinnedIds())
        assertEquals(setOf(4L), e.db.mutedIds())

        val byId = e.repo.observeConversations().first().associateBy { it.threadId.value }
        assertTrue(byId.getValue(2).isStarred)
        assertFalse(byId.getValue(1).isStarred)
        assertTrue(byId.getValue(3).isPinned)
        assertTrue(byId.getValue(4).isMuted)
        assertFalse(byId.getValue(5).isMuted)
    }

    @Test fun `setStar setPin setMute on an empty selection change nothing`() = runTest {
        val e = env()
        e.repo.setStar(ids(1), true)
        e.repo.setPin(ids(2), true)
        e.repo.setMute(ids(3), true)
        for (v in listOf(true, false)) {
            e.repo.setStar(emptyList(), v)
            e.repo.setPin(emptyList(), v)
            e.repo.setMute(emptyList(), v)
        }
        assertEquals(setOf(1L), e.db.starredIds())
        assertEquals(setOf(2L), e.db.pinnedIds())
        assertEquals(setOf(3L), e.db.mutedIds())
    }

    // --- delete ------------------------------------------------------------------

    /** Seeds every app-owned row type on threads 1..3; returns the sender addresses. */
    private suspend fun seedAppRows(db: NoSpamDatabase): Map<Long, String> {
        val addr = (1L..3L).associateWith { "+9891200000$it" }
        for (t in 1L..3L) {
            db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = t, isSpam = true, score = 0.9))
            db.messageVerdictDao.insert(MessageVerdictEntity(t * 100 + 1, t, addr.getValue(t), true, 0.9))
            db.messageVerdictDao.insert(MessageVerdictEntity(t * 100 + 2, t, addr.getValue(t), false, 0.1))
            db.archivedDao.archive(t)
            db.pinnedDao.pin(t)
            db.starredDao.star(t)
            db.mutedDao.mute(t)
            db.senderStateDao.upsert(
                SenderStateEntity(addr.getValue(t), ThreadSpamState.SPAM, spamCount = 3, isUserOverride = true)
            )
        }
        return addr
    }

    @Test fun `deleteConversations is one telephony call and clears every app row of those threads`() = runTest {
        val e = env()
        val addr = seedAppRows(e.db)

        e.repo.deleteConversations(ids(1, 2))

        assertEquals(1, e.tele.deleteConversationsCalls.size)
        assertEquals(setOf(1L, 2L), e.tele.deleteConversationsCalls.single().toSet())
        for (t in listOf(1L, 2L)) {
            assertNull("spam verdict $t", e.db.spamVerdictDao.getByThread(t))
            assertTrue("message verdicts $t", e.db.messageVerdictDao.getByThread(t).isEmpty())
        }
        assertEquals(setOf(3L), e.db.archivedIds())
        assertEquals(setOf(3L), e.db.pinnedIds())
        assertEquals(setOf(3L), e.db.starredIds())
        assertEquals(setOf(3L), e.db.mutedIds())

        // Other threads untouched.
        assertNotNull(e.db.spamVerdictDao.getByThread(3))
        assertEquals(2, e.db.messageVerdictDao.getByThread(3).size)

        // Sender state is keyed by address and survives the deletion.
        for (t in 1L..3L) {
            val s = e.db.senderStateDao.getByAddress(addr.getValue(t))
            assertEquals("sender state of thread $t", ThreadSpamState.SPAM, s?.state)
            assertEquals(3, s!!.spamCount)
            assertTrue(s.isUserOverride)
        }
    }

    @Test fun `deleteConversation of one thread clears its pin star and mute too`() = runTest {
        val e = env()
        val addr = seedAppRows(e.db)

        e.repo.deleteConversation(ThreadId(2))

        assertEquals(listOf(2L), e.tele.deletedThreadIds)
        assertNull(e.db.spamVerdictDao.getByThread(2))
        assertTrue(e.db.messageVerdictDao.getByThread(2).isEmpty())
        assertEquals(setOf(1L, 3L), e.db.archivedIds())
        assertEquals(setOf(1L, 3L), e.db.pinnedIds())
        assertEquals(setOf(1L, 3L), e.db.starredIds())
        assertEquals(setOf(1L, 3L), e.db.mutedIds())
        assertNotNull(e.db.senderStateDao.getByAddress(addr.getValue(2)))
    }

    @Test fun `a recycled thread id does not inherit flags from a deleted conversation`() = runTest {
        val e = env()
        e.repo.setPin(ids(1), true)
        e.repo.setStar(ids(1), true)
        e.repo.setMute(ids(1), true)
        e.repo.deleteConversations(ids(1))
        // The provider hands thread id 1 to a new, unrelated conversation.
        e.tele.emitConversations(listOf(conv(1, "+98999"), conv(2)))
        val c = e.repo.observeConversations().first().first { it.threadId.value == 1L }
        assertFalse(c.isPinned)
        assertFalse(c.isStarred)
        assertFalse(c.isMuted)
        assertFalse(c.isArchived)
    }

    @Test fun `deleteConversations on an empty selection does nothing`() = runTest {
        val e = env()
        seedAppRows(e.db)
        e.repo.deleteConversations(emptyList())
        assertTrue(e.tele.deleteConversationsCalls.isEmpty())
        assertTrue(e.tele.deletedThreadIds.isEmpty())
        assertEquals(setOf(1L, 2L, 3L), e.db.pinnedIds())
        assertEquals(setOf(1L, 2L, 3L), e.db.archivedIds())
        assertNotNull(e.db.spamVerdictDao.getByThread(1))
    }
}

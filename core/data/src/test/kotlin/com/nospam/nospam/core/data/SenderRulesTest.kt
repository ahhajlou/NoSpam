// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender rules: the blocked list (`BlocklistRepository.observeBlockedSenders`)
 * and the "Not spam" list (`SpamRepository.observeAllowedSenders` /
 * `removeAllow`).
 *
 * Written from the feature spec independently of the implementation: nothing
 * here was derived from reading the repositories' bodies.
 */
class SenderRulesTest {

    // ---------------------------------------------------------------- blocked

    @Test fun `without a telephony source only the app's blocklist rows are listed`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+15550001")
        repo.block("NSTEST_A")

        val blocked = repo.observeBlockedSenders().first()

        assertEquals(setOf("+15550001", "NSTEST_A"), blocked.toSet())
        assertEquals(2, blocked.size)
    }

    @Test fun `app rows are listed newest block first`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.blocklistDao.insert(BlocklistEntity(address = "+15550001", createdAt = 1_000L))
        db.blocklistDao.insert(BlocklistEntity(address = "+15550002", createdAt = 3_000L))
        db.blocklistDao.insert(BlocklistEntity(address = "+15550003", createdAt = 2_000L))

        val blocked = BlocklistRepository(db).observeBlockedSenders().first()

        assertEquals(listOf("+15550002", "+15550003", "+15550001"), blocked)
    }

    @Test fun `system-only numbers follow the app's rows`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply {
            systemBlocked += "+15559001"
            systemBlocked += "+15559002"
        }
        val repo = BlocklistRepository(db, telephony = tele)
        repo.block("+15550001")

        val blocked = repo.observeBlockedSenders().first()

        assertEquals(listOf("+15550001", "+15559001", "+15559002"), blocked)
    }

    @Test fun `a number blocked in both lists appears once, in the app's position`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply {
            systemBlocked += "+15559001"
            systemBlocked += "+15550001"
        }
        val repo = BlocklistRepository(db, telephony = tele)
        repo.block("+15550001")

        val blocked = repo.observeBlockedSenders().first()

        assertEquals(listOf("+15550001", "+15559001"), blocked)
    }

    @Test fun `duplicates across the lists are removed case-insensitively`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply { systemBlocked += "nstest_a" }
        val repo = BlocklistRepository(db, telephony = tele)
        repo.block("NSTEST_A")

        val blocked = repo.observeBlockedSenders().first()

        assertEquals(1, blocked.size)
        assertTrue(blocked.single().equals("NSTEST_A", ignoreCase = true))
    }

    @Test fun `system numbers are normalized by trimming`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply { systemBlocked += "  +15559001  " }
        val repo = BlocklistRepository(db, telephony = tele)

        assertEquals(listOf("+15559001"), repo.observeBlockedSenders().first())
    }

    @Test fun `a system number that differs from an app row only by whitespace appears once`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply { systemBlocked += " +15550001 " }
        val repo = BlocklistRepository(db, telephony = tele)
        repo.block("+15550001")

        assertEquals(listOf("+15550001"), repo.observeBlockedSenders().first())
    }

    @Test fun `blank entries are dropped`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply {
            systemBlocked += ""
            systemBlocked += "   "
            systemBlocked += "+15559001"
        }
        val repo = BlocklistRepository(db, telephony = tele)
        repo.block("+15550001")

        assertEquals(listOf("+15550001", "+15559001"), repo.observeBlockedSenders().first())
    }

    @Test fun `when reading the system list throws, the app's rows are still emitted`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val fake = FakeTelephonyDataSource()
        val failing = object : TelephonyDataSource by fake {
            override suspend fun getSystemBlockedNumbers(): List<String> =
                throw SecurityException("not the default SMS app")
        }
        val repo = BlocklistRepository(db, telephony = failing)
        repo.block("+15550001")

        assertEquals(listOf("+15550001"), repo.observeBlockedSenders().first())
    }

    @Test fun `the list re-emits when the app's blocklist changes`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource().apply { systemBlocked += "+15559001" }
        val repo = BlocklistRepository(db, telephony = tele)

        repo.observeBlockedSenders().test {
            assertEquals(listOf("+15559001"), awaitItem())

            repo.block("+15550001")
            awaitItemEqualTo(listOf("+15550001", "+15559001"))

            repo.unblock("+15550001")
            awaitItemEqualTo(listOf("+15559001"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `unblock removes the address from the list and leaves the others`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+15550001")
        repo.block("+15550002")

        repo.unblock("+15550001")

        assertEquals(listOf("+15550002"), repo.observeBlockedSenders().first())
    }

    @Test fun `an empty blocklist emits an empty list`() = runTest {
        val repo = BlocklistRepository(NoSpamDatabase.inMemory(), telephony = FakeTelephonyDataSource())
        assertEquals(emptyList<String>(), repo.observeBlockedSenders().first())
    }

    // ---------------------------------------------------------------- allowed

    private fun spamRepo(db: NoSpamDatabase) = SpamRepository(db, FakeSpamClassifier.alwaysHam())

    @Test fun `only user-overridden TRUSTED senders are listed as allowed`() = runTest {
        val db = NoSpamDatabase.inMemory()
        with(db.senderStateDao) {
            upsert(SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = 10L))
            upsert(SenderStateEntity("+15550002", ThreadSpamState.TRUSTED, isUserOverride = false, updatedAt = 20L))
            upsert(SenderStateEntity("+15550003", ThreadSpamState.CLEAN, updatedAt = 30L))
            upsert(SenderStateEntity("+15550004", ThreadSpamState.MIXED, spamCount = 1, updatedAt = 40L))
            upsert(SenderStateEntity("+15550005", ThreadSpamState.SPAM, spamCount = 5, updatedAt = 50L))
            upsert(SenderStateEntity("+15550006", ThreadSpamState.BLOCKED, isUserOverride = true, updatedAt = 60L))
            upsert(SenderStateEntity("+15550007", ThreadSpamState.SPAM, isUserOverride = true, updatedAt = 70L))
        }

        assertEquals(listOf("+15550001"), spamRepo(db).observeAllowedSenders().first())
    }

    @Test fun `allowed senders are listed most recently updated first`() = runTest {
        val db = NoSpamDatabase.inMemory()
        with(db.senderStateDao) {
            upsert(SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = 100L))
            upsert(SenderStateEntity("+15550002", ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = 300L))
            upsert(SenderStateEntity("NSTEST_A", ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = 200L))
        }

        assertEquals(
            listOf("+15550002", "NSTEST_A", "+15550001"),
            spamRepo(db).observeAllowedSenders().first(),
        )
    }

    @Test fun `marking a sender not spam adds it to the allowed list`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = spamRepo(db)

        repo.observeAllowedSenders().test {
            assertEquals(emptyList<String>(), awaitItem())
            repo.markSenderNotSpam(ThreadId(7), "+15550001")
            awaitItemEqualTo(listOf("+15550001"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `marking several senders not spam adds all of them`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = spamRepo(db)

        repo.markSendersNotSpam(listOf(ThreadId(7) to "+15550001", ThreadId(8) to "NSTEST_A"))

        assertEquals(setOf("+15550001", "NSTEST_A"), repo.observeAllowedSenders().first().toSet())
    }

    @Test fun `removeAllow on a sender with spam history makes it MIXED and keeps its counts`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(
            SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, spamCount = 3, hamCount = 4, isUserOverride = true)
        )

        spamRepo(db).removeAllow("+15550001")

        val after = db.senderStateDao.getByAddress("+15550001")!!
        assertEquals(ThreadSpamState.MIXED, after.state)
        assertFalse(after.isUserOverride)
        assertEquals(3, after.spamCount)
        assertEquals(4, after.hamCount)
    }

    @Test fun `removeAllow on a sender without spam history makes it CLEAN and keeps its counts`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(
            SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, spamCount = 0, hamCount = 6, isUserOverride = true)
        )

        spamRepo(db).removeAllow("+15550001")

        val after = db.senderStateDao.getByAddress("+15550001")!!
        assertEquals(ThreadSpamState.CLEAN, after.state)
        assertFalse(after.isUserOverride)
        assertEquals(0, after.spamCount)
        assertEquals(6, after.hamCount)
    }

    @Test fun `removeAllow takes the sender off the allowed list`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = 1L))
        db.senderStateDao.upsert(SenderStateEntity("+15550002", ThreadSpamState.TRUSTED, isUserOverride = true, updatedAt = 2L))
        val repo = spamRepo(db)

        repo.observeAllowedSenders().test {
            assertEquals(listOf("+15550002", "+15550001"), awaitItem())
            repo.removeAllow("+15550001")
            awaitItemEqualTo(listOf("+15550002"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `removeAllow leaves every state other than user-overridden TRUSTED untouched`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val seeded = listOf(
            SenderStateEntity("+15550001", ThreadSpamState.CLEAN, hamCount = 2, updatedAt = 11L),
            SenderStateEntity("+15550002", ThreadSpamState.SPAM, spamCount = 4, updatedAt = 12L),
            SenderStateEntity("+15550003", ThreadSpamState.BLOCKED, spamCount = 1, isUserOverride = true, updatedAt = 13L),
            SenderStateEntity("+15550004", ThreadSpamState.TRUSTED, spamCount = 1, hamCount = 9, isUserOverride = false, updatedAt = 14L),
            SenderStateEntity("+15550005", ThreadSpamState.MIXED, spamCount = 2, hamCount = 2, updatedAt = 15L),
            SenderStateEntity("+15550006", ThreadSpamState.SPAM, spamCount = 2, isUserOverride = true, updatedAt = 16L),
        )
        seeded.forEach { db.senderStateDao.upsert(it) }
        val repo = spamRepo(db)

        seeded.forEach { repo.removeAllow(it.normalizedAddress) }

        seeded.forEach { assertEquals(it, db.senderStateDao.getByAddress(it.normalizedAddress)) }
    }

    @Test fun `removeAllow for a sender with no state creates no row`() = runTest {
        val db = NoSpamDatabase.inMemory()

        spamRepo(db).removeAllow("+15550001")

        assertNull(db.senderStateDao.getByAddress("+15550001"))
        assertTrue(db.senderStateDao.getAll().isEmpty())
    }

    @Test fun `removeAllow touches no other sender, verdict, flag or conversation`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val other = SenderStateEntity("+15550002", ThreadSpamState.TRUSTED, hamCount = 3, isUserOverride = true, updatedAt = 5L)
        db.senderStateDao.upsert(SenderStateEntity("+15550001", ThreadSpamState.TRUSTED, spamCount = 1, isUserOverride = true))
        db.senderStateDao.upsert(other)
        val verdicts = listOf(
            MessageVerdictEntity(101, 7, "+15550001", isSpam = true, score = 1.0, createdAt = 1L),
            MessageVerdictEntity(102, 7, "+15550001", isSpam = false, score = -1.0, createdAt = 2L, userLabel = false),
            MessageVerdictEntity(201, 8, "+15550002", isSpam = false, score = -1.0, createdAt = 3L),
        )
        verdicts.forEach { db.messageVerdictDao.insert(it) }
        db.pinnedDao.pin(7)
        db.archivedDao.archive(7)
        db.blocklistDao.insert(BlocklistEntity(address = "+15550003", createdAt = 9L))
        val repo = SpamRepository(db, FakeSpamClassifier.alwaysHam())

        repo.removeAllow("+15550001")

        assertEquals(other, db.senderStateDao.getByAddress("+15550002"))
        assertEquals(verdicts.toSet(), db.messageVerdictDao.observeAll().first().toSet())
        assertTrue(db.pinnedDao.isPinned(7))
        assertEquals(listOf(7L), db.archivedDao.observeAll().first().map { it.threadId })
        assertEquals(listOf("+15550003"), db.blocklistDao.observeAll().first().map { it.address })
    }

    /** Awaits items until one equals [expected]; Turbine's timeout fails the test otherwise. */
    private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitItemEqualTo(expected: T) {
        var item = awaitItem()
        while (item != expected) item = awaitItem()
    }
}

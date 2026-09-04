package com.example.nospam.core.database

import com.example.nospam.core.database.entity.BlocklistEntity
import com.example.nospam.core.database.entity.SpamVerdictEntity
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DatabaseTest {
    @Test fun `blocklist insert and query`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val id = db.blocklistDao.insert(BlocklistEntity(address = "+98912"))
        assertTrue(id > 0)
        assertNotNull(db.blocklistDao.findByAddress("+98912"))
    }

    @Test fun `spam verdict upsert and override`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.2))
        assertTrue(db.spamVerdictDao.getByThread(1)!!.isSpam)
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = false, score = -1.0, isUserOverride = true))
        val v = db.spamVerdictDao.getByThread(1)!!
        assertFalse(v.isSpam)
        assertTrue(v.isUserOverride)
    }

    @Test fun `archived dao tracks flags`() = runTest {
        val db = NoSpamDatabase.inMemory()
        assertFalse(db.archivedDao.isArchived(7))
        db.archivedDao.archive(7)
        assertTrue(db.archivedDao.isArchived(7))
        assertEquals(1, db.archivedDao.observeAll().take(1).toList().first().size)
        db.archivedDao.unarchive(7)
        assertFalse(db.archivedDao.isArchived(7))
    }

    @Test fun `clearAutoSpam respects override`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0, isUserOverride = false))
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 2, isSpam = true, score = 1.0, isUserOverride = true))
        db.spamVerdictDao.clearAutoSpam()
        assertNull(db.spamVerdictDao.getByThread(1))
        assertNotNull(db.spamVerdictDao.getByThread(2))
    }
}

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BlocklistRepository, split out of the original RepositoryTest (Wave 2A). */
class BlocklistRepositoryTest {

    @Test fun `block then unblock`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+98912")
        assertTrue(repo.isBlocked("+98912"))
        repo.unblock("+98912")
        assertFalse(repo.isBlocked("+98912"))
    }

    /**
     * Unblocking has to undo the sender state too, not just the blocklist row.
     * Ingress writes `sender_state = BLOCKED` on the first message that arrives
     * while a sender is blocked, and ConversationsRepository routes any BLOCKED
     * sender into Spam. Without this the conversation never returns to the
     * inbox, because ThreadSpamPolicy keeps returning BLOCKED unchanged.
     */
    @Test fun `unblock returns a sender with spam history to MIXED`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+98912")
        // What ingress writes when a message arrives from a blocked sender.
        db.senderStateDao.upsert(
            SenderStateEntity("+98912", ThreadSpamState.BLOCKED, spamCount = 2, isUserOverride = true)
        )

        repo.unblock("+98912")

        val after = db.senderStateDao.getByAddress("+98912")
        assertEquals(ThreadSpamState.MIXED, after?.state)
        assertFalse(after!!.isUserOverride)
        assertEquals(2, after.spamCount)
    }

    @Test fun `unblock returns a sender with no spam history to CLEAN`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+98913")
        db.senderStateDao.upsert(
            SenderStateEntity("+98913", ThreadSpamState.BLOCKED, spamCount = 0, hamCount = 4, isUserOverride = true)
        )

        repo.unblock("+98913")

        assertEquals(ThreadSpamState.CLEAN, db.senderStateDao.getByAddress("+98913")?.state)
    }

    @Test fun `unblock leaves a sender state that is not BLOCKED alone`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        db.senderStateDao.upsert(
            SenderStateEntity("+98914", ThreadSpamState.TRUSTED, hamCount = 9, isUserOverride = true)
        )

        repo.unblock("+98914")

        val after = db.senderStateDao.getByAddress("+98914")
        assertEquals(ThreadSpamState.TRUSTED, after?.state)
        assertTrue(after!!.isUserOverride)
    }

    @Test fun `unblock on a sender with no state row writes nothing`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+98915")

        repo.unblock("+98915")

        assertNull(db.senderStateDao.getByAddress("+98915"))
    }

    @Test fun `an address never blocked is reported as not blocked`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        assertFalse(repo.isBlocked("+98999"))
    }
}

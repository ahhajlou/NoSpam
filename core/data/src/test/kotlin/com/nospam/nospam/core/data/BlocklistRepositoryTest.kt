package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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

    @Test fun `an address never blocked is reported as not blocked`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        assertFalse(repo.isBlocked("+98999"))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-memory DAOs honour the same contracts as the SQLite ones, which the
 * device tests check: tests in other modules rely on them behaving alike.
 */
class InMemoryDaoContractTest {

    @Test fun `insertAll keeps a user label already stored`() = runTest {
        val dao = InMemoryMessageVerdictDao()
        dao.insert(MessageVerdictEntity(1, 1, "+98911", isSpam = true, score = 1.0))
        dao.updateUserLabel(1, false)

        dao.insertAll(listOf(MessageVerdictEntity(1, 1, "+98911", isSpam = true, score = 2.0)))

        assertEquals(false, dao.getByMessageId(1)?.userLabel)
        assertEquals(2.0, dao.getByMessageId(1)!!.score, 0.0)
    }

    @Test fun `getByAddresses returns only the rows that exist`() = runTest {
        val dao = InMemorySenderStateDao()
        dao.upsert(SenderStateEntity("+98911", ThreadSpamState.CLEAN, hamCount = 1))
        val found = dao.getByAddresses(listOf("+98911", "+98922"))
        assertEquals(setOf("+98911"), found.keys)
        assertNull(found["+98922"])
    }

    @Test fun `writeScanBatch writes states and verdicts`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.writeScanBatch(
            listOf(SenderStateEntity("+98911", ThreadSpamState.SUSPECTED, spamCount = 1)),
            listOf(MessageVerdictEntity(1, 1, "+98911", isSpam = true, score = 2.0)),
        )
        assertEquals(ThreadSpamState.SUSPECTED, db.senderStateDao.getByAddress("+98911")?.state)
        assertEquals(true, db.messageVerdictDao.getByMessageId(1)?.isSpam)
    }
}

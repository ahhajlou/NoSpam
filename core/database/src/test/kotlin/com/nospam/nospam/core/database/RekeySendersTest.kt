// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Black-box contract for [NoSpamDatabase.rekeySenders]. */
class RekeySendersTest {

    private fun state(key: String, s: ThreadSpamState, spam: Int = 0, ham: Int = 0, override: Boolean = false, at: Long = 1L) =
        SenderStateEntity(normalizedAddress = key, state = s, spamCount = spam, hamCount = ham, isUserOverride = override, updatedAt = at)

    private fun verdict(id: Long, key: String, spam: Boolean = true, label: Boolean? = null) =
        MessageVerdictEntity(messageId = id, threadId = 100 + id, normalizedAddress = key, isSpam = spam, score = 0.9, createdAt = 5000 + id, userLabel = label)

    private suspend fun NoSpamDatabase.verdicts() = messageVerdictDao.observeAll().first().sortedBy { it.messageId }
    private suspend fun NoSpamDatabase.blocks() = blocklistDao.observeAll().first()
    private suspend fun NoSpamDatabase.states() = senderStateDao.getAll().associateBy { it.normalizedAddress }

    @Test
    fun `empty change is a no-op`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(state("5000301630", ThreadSpamState.SPAM, spam = 3))
        db.messageVerdictDao.insert(verdict(1, "5000301630"))
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630", createdAt = 1))

        db.rekeySenders(SenderRekey(emptyMap(), emptyList()))

        assertEquals(setOf("5000301630"), db.states().keys)
        assertEquals(listOf("5000301630"), db.verdicts().map { it.normalizedAddress })
        assertEquals(listOf("5000301630"), db.blocks().map { it.address })
    }

    @Test
    fun `empty map with merged states is still a no-op`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.rekeySenders(SenderRekey(emptyMap(), listOf(state("X", ThreadSpamState.SPAM, spam = 2))))
        assertEquals(emptyMap<String, SenderStateEntity>(), db.states())
    }

    @Test
    fun `old state rows are removed and merged rows stored, replacing same key`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(state("5000301630", ThreadSpamState.SPAM, spam = 10))
        db.senderStateDao.upsert(state("+985000301630", ThreadSpamState.SUSPECTED, spam = 1))
        db.senderStateDao.upsert(state("+989121234567", ThreadSpamState.MIXED, spam = 1, ham = 2))
        val merged = state("+985000301630", ThreadSpamState.SPAM, spam = 11, at = 99)

        db.rekeySenders(SenderRekey(mapOf("5000301630" to "+985000301630"), listOf(merged)))

        val states = db.states()
        assertEquals(setOf("+985000301630", "+989121234567"), states.keys)
        assertEquals(merged, states["+985000301630"])
        assertNull(db.senderStateDao.getByAddress("5000301630"))
        // Untouched row.
        assertEquals(state("+989121234567", ThreadSpamState.MIXED, spam = 1, ham = 2), states["+989121234567"])
    }

    @Test
    fun `verdicts move to the new key with every other field unchanged`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.messageVerdictDao.insert(verdict(1, "5000301630"))
        db.messageVerdictDao.insert(verdict(2, "5000301630", spam = false, label = true))
        db.messageVerdictDao.insert(verdict(3, "5000301630", label = false))
        db.messageVerdictDao.insert(verdict(4, "+985000301630"))
        db.messageVerdictDao.insert(verdict(5, "+989121234567", spam = false))

        db.rekeySenders(SenderRekey(mapOf("5000301630" to "+985000301630"), emptyList()))

        val v = db.verdicts()
        assertEquals(
            listOf(
                verdict(1, "+985000301630"),
                verdict(2, "+985000301630", spam = false, label = true),
                verdict(3, "+985000301630", label = false),
                verdict(4, "+985000301630"),
                verdict(5, "+989121234567", spam = false),
            ),
            v,
        )
        assertEquals(true, db.messageVerdictDao.getByMessageId(2)?.userLabel)
        assertEquals(false, db.messageVerdictDao.getByMessageId(3)?.userLabel)
    }

    @Test
    fun `block-list entry moves to the new key`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630", reason = "r", createdAt = 1))
        db.blocklistDao.insert(BlocklistEntity(address = "+989121234567", createdAt = 2))

        db.rekeySenders(SenderRekey(mapOf("5000301630" to "+985000301630"), emptyList()))

        assertEquals(setOf("+985000301630", "+989121234567"), db.blocks().map { it.address }.toSet())
        assertNull(db.blocklistDao.findByAddress("5000301630"))
        assertEquals("+985000301630", db.blocklistDao.findByAddress("+985000301630")?.address)
    }

    @Test
    fun `block-list entry onto an existing new-key entry leaves one entry`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630", createdAt = 1))
        db.blocklistDao.insert(BlocklistEntity(address = "+985000301630", createdAt = 2))

        db.rekeySenders(SenderRekey(mapOf("5000301630" to "+985000301630"), emptyList()))

        assertEquals(listOf("+985000301630"), db.blocks().map { it.address })
    }

    @Test
    fun `two old keys moving to one new key leave one block entry`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630", createdAt = 1))
        db.blocklistDao.insert(BlocklistEntity(address = "05000301630", createdAt = 2))

        db.rekeySenders(
            SenderRekey(
                mapOf("5000301630" to "+985000301630", "05000301630" to "+985000301630"),
                emptyList(),
            ),
        )

        assertEquals(listOf("+985000301630"), db.blocks().map { it.address })
    }

    @Test
    fun `two old keys to one new key - states and verdicts`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(state("5000301630", ThreadSpamState.SPAM, spam = 2))
        db.senderStateDao.upsert(state("05000301630", ThreadSpamState.SUSPECTED, spam = 1))
        db.messageVerdictDao.insert(verdict(1, "5000301630"))
        db.messageVerdictDao.insert(verdict(2, "05000301630"))
        val merged = state("+985000301630", ThreadSpamState.SPAM, spam = 3)

        db.rekeySenders(
            SenderRekey(
                mapOf("5000301630" to "+985000301630", "05000301630" to "+985000301630"),
                listOf(merged),
            ),
        )

        assertEquals(mapOf("+985000301630" to merged), db.states())
        assertEquals(listOf("+985000301630", "+985000301630"), db.verdicts().map { it.normalizedAddress })
    }

    @Test
    fun `rows not mentioned are untouched`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val keep = state("SNAPP", ThreadSpamState.TRUSTED, override = true, at = 7)
        db.senderStateDao.upsert(keep)
        db.messageVerdictDao.insert(verdict(9, "SNAPP", label = false))
        db.blocklistDao.insert(BlocklistEntity(address = "BANK", reason = "x", createdAt = 3))

        db.rekeySenders(SenderRekey(mapOf("5000301630" to "+985000301630"), emptyList()))

        assertEquals(mapOf("SNAPP" to keep), db.states())
        assertEquals(listOf(verdict(9, "SNAPP", label = false)), db.verdicts())
        val block = db.blocks().single()
        assertEquals("BANK", block.address)
        assertEquals("x", block.reason)
        assertEquals(3L, block.createdAt)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [NoSpamDatabase.rekeySenders] on real SQLite: the old rows go, the merged row
 * takes their place, verdicts and the block list follow the new key, and every
 * flow shows the result.
 */
@RunWith(AndroidJUnit4::class)
class RekeySendersDeviceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { context.deleteDatabase("nospam.db") }

    @After fun tearDown() { context.deleteDatabase("nospam.db") }

    @Test
    fun two_spellings_become_one_sender_in_every_table() = runTest {
        val db = NoSpamDatabase.persistent(context)
        db.senderStateDao.upsert(SenderStateEntity("5000301630", ThreadSpamState.SPAM, spamCount = 10))
        db.senderStateDao.upsert(SenderStateEntity("+985000301630", ThreadSpamState.SUSPECTED, spamCount = 1))
        db.senderStateDao.upsert(SenderStateEntity("+989121234567", ThreadSpamState.CLEAN, hamCount = 3))
        db.messageVerdictDao.insert(MessageVerdictEntity(1, 7, "5000301630", isSpam = true, score = 1.0))
        db.messageVerdictDao.updateUserLabel(1, true)
        db.messageVerdictDao.insert(MessageVerdictEntity(2, 7, "+985000301630", isSpam = true, score = 1.0))
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630"))
        // Load the flows first, so the change must reach them.
        db.senderStateDao.observeAll().first()
        db.messageVerdictDao.observeAll().first()
        db.blocklistDao.observeAll().first()

        db.rekeySenders(
            SenderRekey(
                newKeyFor = mapOf("5000301630" to "+985000301630"),
                mergedStates = listOf(SenderStateEntity("+985000301630", ThreadSpamState.SPAM, spamCount = 11)),
            )
        )

        assertNull(db.senderStateDao.getByAddress("5000301630"))
        assertEquals(11, db.senderStateDao.getByAddress("+985000301630")?.spamCount)
        assertEquals(
            setOf("+985000301630", "+989121234567"),
            db.senderStateDao.observeAll().first().map { it.normalizedAddress }.toSet(),
        )
        assertEquals(setOf("+985000301630"), db.messageVerdictDao.observeAll().first().map { it.normalizedAddress }.toSet())
        assertEquals(true, db.messageVerdictDao.getByMessageId(1)?.userLabel)
        assertEquals(listOf("+985000301630"), db.blocklistDao.observeAll().first().map { it.address })
        assertNull(db.blocklistDao.findByAddress("5000301630"))
    }

    @Test
    fun a_block_entry_already_under_the_new_key_is_kept_once() = runTest {
        val db = NoSpamDatabase.persistent(context)
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630"))
        db.blocklistDao.insert(BlocklistEntity(address = "+985000301630"))

        db.rekeySenders(SenderRekey(mapOf("5000301630" to "+985000301630"), emptyList()))

        assertEquals(listOf("+985000301630"), db.blocklistDao.observeAll().first().map { it.address })
    }
}

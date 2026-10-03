// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [NoSpamDatabase.writeScanBatch] on real SQLite: both tables written together,
 * both flows published, and a user label already stored kept.
 */
@RunWith(AndroidJUnit4::class)
class ScanBatchDeviceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { context.deleteDatabase("nospam.db") }

    @After fun tearDown() { context.deleteDatabase("nospam.db") }

    @Test
    fun a_batch_writes_states_and_verdicts_and_publishes_both() = runTest {
        val db = NoSpamDatabase.persistent(context)
        // Load both flows first, so the batch is published as a delta.
        db.senderStateDao.observeAll().first()
        db.messageVerdictDao.observeAll().first()
        db.messageVerdictDao.insert(MessageVerdictEntity(1, 1, "+98911", isSpam = true, score = 1.0))
        db.messageVerdictDao.updateUserLabel(1, false)

        db.writeScanBatch(
            listOf(SenderStateEntity("+98911", ThreadSpamState.SUSPECTED, spamCount = 1)),
            listOf(
                MessageVerdictEntity(1, 1, "+98911", isSpam = true, score = 2.0),
                MessageVerdictEntity(2, 1, "+98911", isSpam = true, score = 2.0),
            ),
        )

        assertEquals(ThreadSpamState.SUSPECTED, db.senderStateDao.getByAddress("+98911")?.state)
        assertEquals(listOf("+98911"), db.senderStateDao.observeAll().first().map { it.normalizedAddress })
        assertEquals(setOf(1L, 2L), db.messageVerdictDao.observeAll().first().map { it.messageId }.toSet())
        assertEquals(false, db.messageVerdictDao.getByMessageId(1)?.userLabel)
        assertEquals(false, db.messageVerdictDao.observeAll().first().first { it.messageId == 1L }.userLabel)
    }

    @Test
    fun a_batch_with_no_states_still_writes_its_verdicts() = runTest {
        val db = NoSpamDatabase.persistent(context)
        db.writeScanBatch(emptyList(), listOf(MessageVerdictEntity(5, 1, "+98911", isSpam = false, score = -1.0)))
        assertEquals(false, db.messageVerdictDao.getByMessageId(5)?.isSpam)
    }
}

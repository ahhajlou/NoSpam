// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import com.nospam.nospam.core.database.dao.ArchivedDao
import com.nospam.nospam.core.database.dao.BlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryArchivedDao
import com.nospam.nospam.core.database.dao.InMemoryBlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryMessageVerdictDao
import com.nospam.nospam.core.database.dao.InMemoryModelMetadataDao
import com.nospam.nospam.core.database.dao.InMemoryMutedDao
import com.nospam.nospam.core.database.dao.InMemoryPinnedDao
import com.nospam.nospam.core.database.dao.InMemorySenderStateDao
import com.nospam.nospam.core.database.dao.InMemorySpamVerdictDao
import com.nospam.nospam.core.database.dao.InMemoryStarredDao
import com.nospam.nospam.core.database.dao.MessageVerdictDao
import com.nospam.nospam.core.database.dao.ModelMetadataDao
import com.nospam.nospam.core.database.dao.MutedDao
import com.nospam.nospam.core.database.dao.PinnedDao
import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.dao.SpamVerdictDao
import com.nospam.nospam.core.database.dao.StarredDao
import com.nospam.nospam.core.database.dao.SqliteMessageVerdictDao
import com.nospam.nospam.core.database.dao.SqliteSenderStateDao
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.dao.SqliteBlocklistDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NoSpamDatabase(
    val blocklistDao: BlocklistDao = InMemoryBlocklistDao(),
    val spamVerdictDao: SpamVerdictDao = InMemorySpamVerdictDao(),
    val modelMetadataDao: ModelMetadataDao = InMemoryModelMetadataDao(),
    val archivedDao: ArchivedDao = InMemoryArchivedDao(),
    val messageVerdictDao: MessageVerdictDao = InMemoryMessageVerdictDao(),
    val senderStateDao: SenderStateDao = InMemorySenderStateDao(),
    val starredDao: StarredDao = InMemoryStarredDao(),
    val pinnedDao: PinnedDao = InMemoryPinnedDao(),
    val mutedDao: MutedDao = InMemoryMutedDao(),
    private val scanBatch: suspend (List<SenderStateEntity>, List<MessageVerdictEntity>) -> Unit = { states, verdicts ->
        senderStateDao.upsertAll(states)
        messageVerdictDao.insertAll(verdicts)
    },
    private val rekey: suspend (SenderRekey) -> Unit = { change ->
        change.newKeyFor.keys.forEach { senderStateDao.deleteByAddress(it) }
        senderStateDao.upsertAll(change.mergedStates)
        val verdicts = messageVerdictDao.observeAll().first()
        messageVerdictDao.insertAll(
            verdicts.mapNotNull { v -> change.newKeyFor[v.normalizedAddress]?.let { v.copy(normalizedAddress = it) } }
        )
        val blocked = blocklistDao.observeAll().first()
        val kept = blocked.map { it.address }.toMutableSet()
        blocked.forEach { entry ->
            val newKey = change.newKeyFor[entry.address] ?: return@forEach
            blocklistDao.deleteByAddress(entry.address)
            kept.remove(entry.address)
            if (kept.add(newKey)) blocklistDao.insert(entry.copy(id = 0, address = newKey))
        }
    },
) {
    /**
     * One history-scan batch: sender states and message verdicts in a single
     * transaction. Written separately, a crash between them left counts that
     * included messages with no verdict, and the next scan, which skips only
     * messages with a verdict, counted them again. Verdicts keep any user label
     * already stored (see [MessageVerdictDao.insertAll]).
     */
    suspend fun writeScanBatch(states: List<SenderStateEntity>, verdicts: List<MessageVerdictEntity>) =
        scanBatch(states, verdicts)

    /**
     * Moves everything keyed by sender to new keys, in one transaction: the old
     * `sender_state` rows go and [SenderRekey.mergedStates] take their place,
     * and verdicts and block-list entries point at the new key. A crash halfway
     * would otherwise leave merged counts beside the rows they were summed from,
     * and the next run would add them again. No-op when there is nothing to move.
     */
    suspend fun rekeySenders(change: SenderRekey) {
        if (change.newKeyFor.isEmpty()) return
        rekey(change)
    }

    companion object {
        fun inMemory(): NoSpamDatabase = NoSpamDatabase()

        fun persistent(context: android.content.Context): NoSpamDatabase {
            val helper = SqliteNoSpamOpenHelper(context.applicationContext)
            val senderStates = com.nospam.nospam.core.database.dao.SqliteSenderStateDao(helper)
            val verdicts = com.nospam.nospam.core.database.dao.SqliteMessageVerdictDao(helper)
            val blocklist = com.nospam.nospam.core.database.dao.SqliteBlocklistDao(helper)
            return NoSpamDatabase(
                blocklistDao = blocklist,
                spamVerdictDao = com.nospam.nospam.core.database.dao.SqliteSpamVerdictDao(helper),
                modelMetadataDao = com.nospam.nospam.core.database.dao.SqliteModelMetadataDao(helper),
                archivedDao = com.nospam.nospam.core.database.dao.SqliteArchivedDao(helper),
                messageVerdictDao = verdicts,
                senderStateDao = senderStates,
                starredDao = com.nospam.nospam.core.database.dao.SqliteStarredDao(helper),
                pinnedDao = com.nospam.nospam.core.database.dao.SqlitePinnedDao(helper),
                mutedDao = com.nospam.nospam.core.database.dao.SqliteMutedDao(helper),
                scanBatch = { states, rows -> writeScanBatch(helper, senderStates, verdicts, states, rows) },
                rekey = { change -> rekeySenders(helper, senderStates, verdicts, blocklist, change) },
            )
        }
    }
}

/**
 * Both tables in one SQLite transaction on one thread (a transaction is bound to
 * its thread). Holds both DAOs' write locks, always states then verdicts; nothing
 * else takes both, so the order cannot deadlock. Each flow is published only
 * after the commit.
 */
private suspend fun writeScanBatch(
    helper: SqliteNoSpamOpenHelper,
    senderStates: SqliteSenderStateDao,
    verdicts: SqliteMessageVerdictDao,
    states: List<SenderStateEntity>,
    rows: List<MessageVerdictEntity>,
) = withContext(Dispatchers.IO) {
    if (states.isEmpty() && rows.isEmpty()) return@withContext
    senderStates.writeLock.withLock {
        verdicts.writeLock.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            val written = try {
                senderStates.writeRows(states)
                verdicts.writeKeepingUserLabels(rows).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
            if (states.isNotEmpty()) senderStates.publishWritten(states)
            if (written.isNotEmpty()) verdicts.publishWritten(written)
        }
    }
}

/**
 * Sender keys that change: each old key mapped to its new one, and the
 * `sender_state` rows to store under the new keys (one per new key, already
 * merged). Every old key's row is removed.
 */
data class SenderRekey(
    val newKeyFor: Map<String, String>,
    val mergedStates: List<SenderStateEntity>,
)

/**
 * [NoSpamDatabase.rekeySenders] in one SQLite transaction, holding the three
 * tables' write locks in the order states, verdicts, block list. Every flow is
 * re-read after the commit.
 */
private suspend fun rekeySenders(
    helper: SqliteNoSpamOpenHelper,
    senderStates: SqliteSenderStateDao,
    verdicts: SqliteMessageVerdictDao,
    blocklist: SqliteBlocklistDao,
    change: SenderRekey,
) = withContext(Dispatchers.IO) {
    senderStates.writeLock.withLock {
        verdicts.writeLock.withLock {
            blocklist.writeLock.withLock {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    for ((old, new) in change.newKeyFor) {
                        db.delete("sender_state", "normalizedAddress = ?", arrayOf(old))
                        db.execSQL("UPDATE message_verdict SET normalizedAddress = ? WHERE normalizedAddress = ?", arrayOf(new, old))
                        // An entry already under the new key wins; the old one goes.
                        db.execSQL("UPDATE OR IGNORE blocklist SET address = ? WHERE address = ?", arrayOf(new, old))
                        db.delete("blocklist", "address = ?", arrayOf(old))
                    }
                    senderStates.writeRows(change.mergedStates)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                senderStates.load()
                verdicts.load()
                blocklist.load()
            }
        }
    }
}

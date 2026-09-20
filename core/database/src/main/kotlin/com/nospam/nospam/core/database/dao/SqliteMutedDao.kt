// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.MutedThreadEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteMutedDao(private val helper: SqliteNoSpamOpenHelper) : MutedDao {
    private val flow = MutableStateFlow<List<MutedThreadEntity>>(emptyList())
    private val initialized = AtomicBoolean(false)
    // Serializes each mutate-then-refresh pair. Without it two writers
    // could publish their snapshots out of order and strand the flow on
    // a stale list until the next write to this table.
    private val writeLock = Mutex()

    /** True once [flow] holds a full snapshot. Guarded by [writeLock]. */
    private var loaded = false

    /** Reads the whole table. Caller must hold [writeLock]. */
    private fun load() {
        flow.value = readAllSync()
        loaded = true
        initialized.set(true)
    }

    /**
     * Publishes a write by applying [delta] to the current snapshot instead of
     * re-reading the table, so the critical section stays O(1). Falls back to a
     * full read while the flow has no snapshot to apply a delta to. Caller must
     * hold [writeLock].
     */
    private fun publish(delta: (List<MutedThreadEntity>) -> List<MutedThreadEntity>) {
        if (loaded) {
            flow.value = delta(flow.value)
            initialized.set(true)
        } else {
            load()
        }
    }
    private fun readAllSync(): List<MutedThreadEntity> {
        val list = mutableListOf<MutedThreadEntity>()
        helper.readableDatabase.query("muted_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) list.add(MutedThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
        }
        return list
    }
    override fun observeAll(): Flow<List<MutedThreadEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                writeLock.withLock { if (!loaded) load() }
            }
        }
    }
    override suspend fun mute(threadId: Long) = withContext(Dispatchers.IO){ writeLock.withLock { val v = android.content.ContentValues().apply{ put("threadId", threadId)}; helper.writableDatabase.insertWithOnConflict("muted_threads", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE); publish { it.withThread(threadId) } } }
    override suspend fun unmute(threadId: Long) { withContext(Dispatchers.IO){ writeLock.withLock { helper.writableDatabase.delete("muted_threads", "threadId = ?", arrayOf(threadId.toString())); publish { it.withoutThread(threadId) } } } }
    override suspend fun isMuted(threadId: Long): Boolean = withContext(Dispatchers.IO){ helper.readableDatabase.query("muted_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null).use{ it.count>0 } }
}

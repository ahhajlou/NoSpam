// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.ArchivedThreadEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteArchivedDao(
    private val helper: SqliteNoSpamOpenHelper
) : ArchivedDao {
    private val flow = MutableStateFlow<List<ArchivedThreadEntity>>(emptyList())
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
    private fun publish(delta: (List<ArchivedThreadEntity>) -> List<ArchivedThreadEntity>) {
        if (loaded) {
            flow.value = delta(flow.value)
            initialized.set(true)
        } else {
            load()
        }
    }

    private fun readAllSync(): List<ArchivedThreadEntity> {
        val list = mutableListOf<ArchivedThreadEntity>()
        helper.readableDatabase.query("archived_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                list.add(ArchivedThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
            }
        }
        return list
    }

    override fun observeAll(): Flow<List<ArchivedThreadEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                writeLock.withLock { if (!loaded) load() }
            }
        }
    }

    override suspend fun archive(threadId: Long) {
        withContext(Dispatchers.IO) { writeLock.withLock {
            val values = android.content.ContentValues().apply { put("threadId", threadId) }
            helper.writableDatabase.insertWithOnConflict(
                "archived_threads", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            publish { it.withThread(threadId) }
        } }
    }

    override suspend fun unarchive(threadId: Long) {
        withContext(Dispatchers.IO) { writeLock.withLock {
            helper.writableDatabase.delete("archived_threads", "threadId = ?", arrayOf(threadId.toString()))
            publish { it.withoutThread(threadId) }
        } }
    }

    override suspend fun archiveAll(threadIds: Collection<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) { writeLock.withLock {
            helper.writableDatabase.insertThreadIds("archived_threads", threadIds)
            publish { current -> threadIds.fold(current) { acc, id -> acc.withThread(id) } }
        } }
    }

    override suspend fun unarchiveAll(threadIds: Collection<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) { writeLock.withLock {
            helper.writableDatabase.deleteThreadIds("archived_threads", threadIds)
            val removed = threadIds.toSet()
            publish { current -> current.filterNot { it.threadId in removed } }
        } }
    }

    override suspend fun isArchived(threadId: Long): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            "archived_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null
        ).use { c -> c.count > 0 }
    }
}

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.PinnedThreadEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqlitePinnedDao(private val helper: SqliteNoSpamOpenHelper) : PinnedDao {
    private val flow = MutableStateFlow<List<PinnedThreadEntity>>(emptyList())
    private val initialized = AtomicBoolean(false)
    // Serializes each mutate-then-refresh pair. Without it two writers
    // could publish their snapshots out of order and strand the flow on
    // a stale list until the next write to this table.
    private val writeLock = Mutex()
    private fun readAllSync(): List<PinnedThreadEntity> {
        val list = mutableListOf<PinnedThreadEntity>()
        helper.readableDatabase.query("pinned_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) list.add(PinnedThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
        }
        return list
    }
    override fun observeAll(): Flow<List<PinnedThreadEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) { writeLock.withLock { flow.value = readAllSync() } }
        }
    }
    override suspend fun pin(threadId: Long) = withContext(Dispatchers.IO){ writeLock.withLock { val v = android.content.ContentValues().apply{ put("threadId", threadId)}; helper.writableDatabase.insertWithOnConflict("pinned_threads", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE); flow.value = readAllSync(); initialized.set(true) } }
    override suspend fun unpin(threadId: Long) { withContext(Dispatchers.IO){ writeLock.withLock { helper.writableDatabase.delete("pinned_threads", "threadId = ?", arrayOf(threadId.toString())); flow.value = readAllSync() } } }
    override suspend fun isPinned(threadId: Long): Boolean = withContext(Dispatchers.IO){ helper.readableDatabase.query("pinned_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null).use{ it.count>0 } }
}

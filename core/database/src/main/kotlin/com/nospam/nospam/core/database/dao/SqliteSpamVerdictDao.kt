package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteSpamVerdictDao(
    private val helper: SqliteNoSpamOpenHelper
) : SpamVerdictDao {
    private val flow = MutableStateFlow<List<SpamVerdictEntity>>(emptyList())
    private val initialized = AtomicBoolean(false)
    // Serializes each mutate-then-refresh pair. Without it two writers
    // could publish their snapshots out of order and strand the flow on
    // a stale list until the next write to this table.
    private val writeLock = Mutex()

    /** True once [flow] holds a full snapshot. Guarded by [writeLock]. */
    private var loaded = false

    /** Reads the whole table. Caller must hold [writeLock]. */
    private fun load() {
        flow.value = readSpamSync()
        loaded = true
        initialized.set(true)
    }

    /**
     * Publishes a write by applying [delta] to the current snapshot instead of
     * re-reading the table, so the critical section stays O(1). Falls back to a
     * full read while the flow has no snapshot to apply a delta to. Caller must
     * hold [writeLock].
     */
    private fun publish(delta: (List<SpamVerdictEntity>) -> List<SpamVerdictEntity>) {
        if (loaded) {
            flow.value = delta(flow.value)
            initialized.set(true)
        } else {
            load()
        }
    }

    private fun readSpamSync(): List<SpamVerdictEntity> {
        val list = mutableListOf<SpamVerdictEntity>()
        helper.readableDatabase.query("spam_verdict", null, "isSpam = 1", null, null, null, null).use { c ->
            while (c.moveToNext()) {
                list.add(cursorToEntity(c))
            }
        }
        return list
    }

    private fun cursorToEntity(c: android.database.Cursor): SpamVerdictEntity =
        SpamVerdictEntity(
            threadId = c.getLong(c.getColumnIndexOrThrow("threadId")),
            isSpam = c.getInt(c.getColumnIndexOrThrow("isSpam")) == 1,
            score = c.getDouble(c.getColumnIndexOrThrow("score")),
            isUserOverride = c.getInt(c.getColumnIndexOrThrow("isUserOverride")) == 1,
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt"))
        )

    override suspend fun getByThread(threadId: Long): SpamVerdictEntity? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            "spam_verdict", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null
        ).use { c ->
            if (c.moveToFirst()) cursorToEntity(c) else null
        }
    }

    override fun observeSpam(): Flow<List<SpamVerdictEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                writeLock.withLock { if (!loaded) load() }
            }
        }
    }

    override suspend fun upsert(entity: SpamVerdictEntity) = withContext(Dispatchers.IO) { writeLock.withLock {
        val values = android.content.ContentValues().apply {
            put("threadId", entity.threadId)
            put("isSpam", if (entity.isSpam) 1 else 0)
            put("score", entity.score)
            put("isUserOverride", if (entity.isUserOverride) 1 else 0)
            put("updatedAt", entity.updatedAt)
        }
        helper.writableDatabase.insertWithOnConflict(
            "spam_verdict", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        publish { it.withSpamVerdict(entity) }
        Unit
    } }

    override suspend fun deleteByThread(threadId: Long) {
        withContext(Dispatchers.IO) { writeLock.withLock {
            helper.writableDatabase.delete("spam_verdict", "threadId = ?", arrayOf(threadId.toString()))
            publish { it.withoutThread(threadId) }
        } }
    }

    override suspend fun clearAutoSpam() {
        withContext(Dispatchers.IO) { writeLock.withLock {
            helper.writableDatabase.delete("spam_verdict", "isSpam = 1 AND isUserOverride = 0", null)
            publish { it.withoutAutoSpam() }
        } }
    }

    override suspend fun deleteAutoSpamOlderThan(cutoffMillis: Long): Int = withContext(Dispatchers.IO) { writeLock.withLock {
        val removed = helper.writableDatabase.delete(
            "spam_verdict",
            "isSpam = 1 AND isUserOverride = 0 AND updatedAt < ?",
            arrayOf(cutoffMillis.toString())
        )
        if (removed > 0) publish { it.withoutAutoSpamBefore(cutoffMillis) }
        removed
    } }
}

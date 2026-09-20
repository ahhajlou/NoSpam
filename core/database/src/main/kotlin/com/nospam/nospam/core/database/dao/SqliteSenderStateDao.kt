// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteSenderStateDao(private val helper: SqliteNoSpamOpenHelper) : SenderStateDao {
    private val flow = MutableStateFlow<List<SenderStateEntity>>(emptyList())
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
    private fun publish(delta: (List<SenderStateEntity>) -> List<SenderStateEntity>) {
        if (loaded) {
            flow.value = delta(flow.value)
            initialized.set(true)
        } else {
            load()
        }
    }
    private fun readAllSync(): List<SenderStateEntity> {
        val list = mutableListOf<SenderStateEntity>()
        helper.readableDatabase.query("sender_state", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SenderStateEntity(
                        normalizedAddress = c.getString(c.getColumnIndexOrThrow("normalizedAddress")),
                        state = ThreadSpamState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
                        spamCount = c.getInt(c.getColumnIndexOrThrow("spamCount")),
                        hamCount = c.getInt(c.getColumnIndexOrThrow("hamCount")),
                        isUserOverride = c.getInt(c.getColumnIndexOrThrow("isUserOverride")) == 1,
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt"))
                    )
                )
            }
        }
        return list
    }
    override fun observeAll(): Flow<List<SenderStateEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                writeLock.withLock { if (!loaded) load() }
            }
        }
    }
    override suspend fun getByAddress(normalizedAddress: String): SenderStateEntity? = withContext(Dispatchers.IO){
        helper.readableDatabase.query("sender_state", null, "normalizedAddress = ?", arrayOf(normalizedAddress), null, null, null).use { c ->
            if (c.moveToFirst()) SenderStateEntity(
                normalizedAddress = c.getString(c.getColumnIndexOrThrow("normalizedAddress")),
                state = ThreadSpamState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
                spamCount = c.getInt(c.getColumnIndexOrThrow("spamCount")),
                hamCount = c.getInt(c.getColumnIndexOrThrow("hamCount")),
                isUserOverride = c.getInt(c.getColumnIndexOrThrow("isUserOverride")) == 1,
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt"))
            ) else null
        }
    }
    override suspend fun getAll(): List<SenderStateEntity> = withContext(Dispatchers.IO) { readAllSync() }
    override suspend fun upsert(entity: SenderStateEntity) { withContext(Dispatchers.IO){ writeLock.withLock {
        upsertEntity(entity)
        publish { it.withState(entity) }
    } }}
    override suspend fun upsertAll(entities: List<SenderStateEntity>) { withContext(Dispatchers.IO){ writeLock.withLock {
        if (entities.isEmpty()) return@withContext
        helper.writableDatabase.beginTransaction()
        try {
            entities.forEach { upsertEntity(it) }
            helper.writableDatabase.setTransactionSuccessful()
        } finally {
            helper.writableDatabase.endTransaction()
        }
        publish { it.withStates(entities) }
    } }}

    private fun upsertEntity(entity: SenderStateEntity) {
        val v = senderStateValues(entity)
        helper.writableDatabase.insertWithOnConflict("sender_state", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun senderStateValues(entity: SenderStateEntity) = android.content.ContentValues().apply {
        put("normalizedAddress", entity.normalizedAddress)
        put("state", entity.state.name)
        put("spamCount", entity.spamCount)
        put("hamCount", entity.hamCount)
        put("isUserOverride", if(entity.isUserOverride)1 else 0)
        put("updatedAt", entity.updatedAt)
    }
    override suspend fun deleteByAddress(normalizedAddress: String) { withContext(Dispatchers.IO){ writeLock.withLock { helper.writableDatabase.delete("sender_state","normalizedAddress = ?", arrayOf(normalizedAddress)); publish { it.withoutAddress(normalizedAddress) } } } }
}

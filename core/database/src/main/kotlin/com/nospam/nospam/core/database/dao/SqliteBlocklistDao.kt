package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.BlocklistEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteBlocklistDao(
    private val helper: SqliteNoSpamOpenHelper
) : BlocklistDao {
    private val flow = MutableStateFlow<List<BlocklistEntity>>(emptyList())
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
    private fun publish(delta: (List<BlocklistEntity>) -> List<BlocklistEntity>) {
        if (loaded) {
            flow.value = delta(flow.value)
            initialized.set(true)
        } else {
            load()
        }
    }

    private fun readAllSync(): List<BlocklistEntity> {
        val db = helper.readableDatabase
        val list = mutableListOf<BlocklistEntity>()
        db.query("blocklist", null, null, null, null, null, "createdAt DESC").use { c ->
            while (c.moveToNext()) {
                list.add(
                    BlocklistEntity(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        address = c.getString(c.getColumnIndexOrThrow("address")),
                        reason = c.getString(c.getColumnIndexOrThrow("reason")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt"))
                    )
                )
            }
        }
        return list
    }

    override fun observeAll(): Flow<List<BlocklistEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                writeLock.withLock { if (!loaded) load() }
            }
        }
    }

    override suspend fun findByAddress(address: String): BlocklistEntity? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            "blocklist", null, "address = ?", arrayOf(address), null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                BlocklistEntity(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    address = c.getString(c.getColumnIndexOrThrow("address")),
                    reason = c.getString(c.getColumnIndexOrThrow("reason")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt"))
                )
            } else null
        }
    }

    override suspend fun insert(entry: BlocklistEntity): Long = withContext(Dispatchers.IO) { writeLock.withLock {
        val db = helper.writableDatabase
        val values = android.content.ContentValues().apply {
            put("address", entry.address)
            put("reason", entry.reason)
            put("createdAt", entry.createdAt)
        }
        // REPLACE on conflict to mimic in-memory behaviour (unique address)
        val id = db.insertWithOnConflict("blocklist", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        // insertWithOnConflict returns -1 when the row was not written; leave the
        // snapshot alone in that case rather than publishing a row that is not there.
        if (id != -1L) publish { it.withEntry(entry.copy(id = id)) }
        id
    } }

    override suspend fun delete(entry: BlocklistEntity) = withContext(Dispatchers.IO) { writeLock.withLock {
        helper.writableDatabase.delete("blocklist", "id = ?", arrayOf(entry.id.toString()))
        publish { it.withoutId(entry.id) }
        Unit
    } }

    override suspend fun deleteByAddress(address: String) {
        withContext(Dispatchers.IO) { writeLock.withLock {
            helper.writableDatabase.delete("blocklist", "address = ?", arrayOf(address))
            publish { it.withoutAddress(address) }
        } }
    }
}

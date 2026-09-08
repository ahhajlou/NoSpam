package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.BlocklistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class SqliteBlocklistDao(
    private val helper: SqliteNoSpamOpenHelper
) : BlocklistDao {
    private val flow = MutableStateFlow<List<BlocklistEntity>>(emptyList())

    init {
        // Load synchronously on creation (on IO thread would be ideal, but
        // we do a blocking read here since helper is cheap and DB is small).
        flow.value = readAllSync()
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

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        flow.value = readAllSync()
    }

    override fun observeAll(): Flow<List<BlocklistEntity>> = flow

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

    override suspend fun insert(entry: BlocklistEntity): Long = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = android.content.ContentValues().apply {
            put("address", entry.address)
            put("reason", entry.reason)
            put("createdAt", entry.createdAt)
        }
        // REPLACE on conflict to mimic in-memory behaviour (unique address)
        val id = db.insertWithOnConflict("blocklist", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        flow.value = readAllSync()
        id
    }

    override suspend fun delete(entry: BlocklistEntity) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("blocklist", "id = ?", arrayOf(entry.id.toString()))
        flow.value = readAllSync()
        Unit
    }

    override suspend fun deleteByAddress(address: String) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("blocklist", "address = ?", arrayOf(address))
            flow.value = readAllSync()
        }
    }
}

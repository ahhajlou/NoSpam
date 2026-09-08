package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class SqliteSenderStateDao(private val helper: SqliteNoSpamOpenHelper) : SenderStateDao {
    private val flow = MutableStateFlow<List<SenderStateEntity>>(emptyList())
    init { flow.value = readAllSync() }
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
    override fun observeAll(): Flow<List<SenderStateEntity>> = flow
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
    override suspend fun upsert(entity: SenderStateEntity) { withContext(Dispatchers.IO){
        val v = android.content.ContentValues().apply {
            put("normalizedAddress", entity.normalizedAddress)
            put("state", entity.state.name)
            put("spamCount", entity.spamCount)
            put("hamCount", entity.hamCount)
            put("isUserOverride", if(entity.isUserOverride)1 else 0)
            put("updatedAt", entity.updatedAt)
        }
        helper.writableDatabase.insertWithOnConflict("sender_state", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        flow.value = readAllSync()
    }}
    override suspend fun deleteByAddress(normalizedAddress: String) { withContext(Dispatchers.IO){ helper.writableDatabase.delete("sender_state","normalizedAddress = ?", arrayOf(normalizedAddress)); flow.value = readAllSync() } }
}

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class SqliteSpamVerdictDao(
    private val helper: SqliteNoSpamOpenHelper
) : SpamVerdictDao {
    private val flow = MutableStateFlow<List<SpamVerdictEntity>>(emptyList())

    init {
        flow.value = readSpamSync()
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

    override fun observeSpam(): Flow<List<SpamVerdictEntity>> = flow

    override suspend fun upsert(entity: SpamVerdictEntity) = withContext(Dispatchers.IO) {
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
        flow.value = readSpamSync()
        Unit
    }

    override suspend fun deleteByThread(threadId: Long) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("spam_verdict", "threadId = ?", arrayOf(threadId.toString()))
            flow.value = readSpamSync()
        }
    }

    override suspend fun clearAutoSpam() {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("spam_verdict", "isSpam = 1 AND isUserOverride = 0", null)
            flow.value = readSpamSync()
        }
    }

    override suspend fun deleteAutoSpamOlderThan(cutoffMillis: Long): Int = withContext(Dispatchers.IO) {
        val removed = helper.writableDatabase.delete(
            "spam_verdict",
            "isSpam = 1 AND isUserOverride = 0 AND updatedAt < ?",
            arrayOf(cutoffMillis.toString())
        )
        if (removed > 0) flow.value = readSpamSync()
        removed
    }
}

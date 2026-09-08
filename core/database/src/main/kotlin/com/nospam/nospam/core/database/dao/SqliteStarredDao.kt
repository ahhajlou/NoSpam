package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.StarredThreadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class SqliteStarredDao(private val helper: SqliteNoSpamOpenHelper) : StarredDao {
    private val flow = MutableStateFlow<List<StarredThreadEntity>>(emptyList())
    init { flow.value = readAllSync() }
    private fun readAllSync(): List<StarredThreadEntity> {
        val list = mutableListOf<StarredThreadEntity>()
        helper.readableDatabase.query("starred_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) list.add(StarredThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
        }
        return list
    }
    override fun observeAll(): Flow<List<StarredThreadEntity>> = flow
    override suspend fun star(threadId: Long) = withContext(Dispatchers.IO){ val v = android.content.ContentValues().apply{ put("threadId", threadId)}; helper.writableDatabase.insertWithOnConflict("starred_threads", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE); flow.value = readAllSync() }
    override suspend fun unstar(threadId: Long) { withContext(Dispatchers.IO){ helper.writableDatabase.delete("starred_threads", "threadId = ?", arrayOf(threadId.toString())); flow.value = readAllSync() } }
    override suspend fun isStarred(threadId: Long): Boolean = withContext(Dispatchers.IO){ helper.readableDatabase.query("starred_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null).use{ it.count>0 } }
}

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.PinnedThreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SqlitePinnedDao(private val helper: SqliteNoSpamOpenHelper) : PinnedDao {
    private val flow = MutableStateFlow<List<PinnedThreadEntity>>(emptyList())
    init {
        CoroutineScope(Dispatchers.IO).launch {
            flow.value = readAllSync()
        }
    }
    private fun readAllSync(): List<PinnedThreadEntity> {
        val list = mutableListOf<PinnedThreadEntity>()
        helper.readableDatabase.query("pinned_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) list.add(PinnedThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
        }
        return list
    }
    override fun observeAll(): Flow<List<PinnedThreadEntity>> = flow
    override suspend fun pin(threadId: Long) = withContext(Dispatchers.IO){ val v = android.content.ContentValues().apply{ put("threadId", threadId)}; helper.writableDatabase.insertWithOnConflict("pinned_threads", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE); flow.value = readAllSync() }
    override suspend fun unpin(threadId: Long) { withContext(Dispatchers.IO){ helper.writableDatabase.delete("pinned_threads", "threadId = ?", arrayOf(threadId.toString())); flow.value = readAllSync() } }
    override suspend fun isPinned(threadId: Long): Boolean = withContext(Dispatchers.IO){ helper.readableDatabase.query("pinned_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null).use{ it.count>0 } }
}

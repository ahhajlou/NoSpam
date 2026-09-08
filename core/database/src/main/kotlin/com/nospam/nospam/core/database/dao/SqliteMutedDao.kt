package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.MutedThreadEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class SqliteMutedDao(private val helper: SqliteNoSpamOpenHelper) : MutedDao {
    private val flow = MutableStateFlow<List<MutedThreadEntity>>(emptyList())
    private val initialized = AtomicBoolean(false)
    private fun readAllSync(): List<MutedThreadEntity> {
        val list = mutableListOf<MutedThreadEntity>()
        helper.readableDatabase.query("muted_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) list.add(MutedThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
        }
        return list
    }
    override fun observeAll(): Flow<List<MutedThreadEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            flow.value = withContext(Dispatchers.IO) { readAllSync() }
        }
    }
    override suspend fun mute(threadId: Long) = withContext(Dispatchers.IO){ val v = android.content.ContentValues().apply{ put("threadId", threadId)}; helper.writableDatabase.insertWithOnConflict("muted_threads", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE); flow.value = readAllSync(); initialized.set(true) }
    override suspend fun unmute(threadId: Long) { withContext(Dispatchers.IO){ helper.writableDatabase.delete("muted_threads", "threadId = ?", arrayOf(threadId.toString())); flow.value = readAllSync() } }
    override suspend fun isMuted(threadId: Long): Boolean = withContext(Dispatchers.IO){ helper.readableDatabase.query("muted_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null).use{ it.count>0 } }
}

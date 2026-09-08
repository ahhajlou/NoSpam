package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.ArchivedThreadEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class SqliteArchivedDao(
    private val helper: SqliteNoSpamOpenHelper
) : ArchivedDao {
    private val flow = MutableStateFlow<List<ArchivedThreadEntity>>(emptyList())
    private val initialized = AtomicBoolean(false)

    private fun readAllSync(): List<ArchivedThreadEntity> {
        val list = mutableListOf<ArchivedThreadEntity>()
        helper.readableDatabase.query("archived_threads", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                list.add(ArchivedThreadEntity(c.getLong(c.getColumnIndexOrThrow("threadId"))))
            }
        }
        return list
    }

    override fun observeAll(): Flow<List<ArchivedThreadEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            flow.value = withContext(Dispatchers.IO) { readAllSync() }
        }
    }

    override suspend fun archive(threadId: Long) {
        withContext(Dispatchers.IO) {
            val values = android.content.ContentValues().apply { put("threadId", threadId) }
            helper.writableDatabase.insertWithOnConflict(
                "archived_threads", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            flow.value = readAllSync()
            initialized.set(true)
        }
    }

    override suspend fun unarchive(threadId: Long) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("archived_threads", "threadId = ?", arrayOf(threadId.toString()))
            flow.value = readAllSync()
        }
    }

    override suspend fun isArchived(threadId: Long): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            "archived_threads", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null
        ).use { c -> c.count > 0 }
    }
}

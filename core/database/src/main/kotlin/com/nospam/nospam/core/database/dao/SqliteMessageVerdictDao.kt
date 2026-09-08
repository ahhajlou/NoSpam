package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class SqliteMessageVerdictDao(private val helper: SqliteNoSpamOpenHelper) : MessageVerdictDao {
    private val flow = MutableStateFlow<List<MessageVerdictEntity>>(emptyList())
    init { flow.value = readAllSync() }
    private fun readAllSync(): List<MessageVerdictEntity> {
        val list = mutableListOf<MessageVerdictEntity>()
        helper.readableDatabase.query("message_verdict", null, null, null, null, null, "createdAt DESC").use { c ->
            while (c.moveToNext()) {
                list.add(
                    MessageVerdictEntity(
                        messageId = c.getLong(c.getColumnIndexOrThrow("messageId")),
                        threadId = c.getLong(c.getColumnIndexOrThrow("threadId")),
                        normalizedAddress = c.getString(c.getColumnIndexOrThrow("normalizedAddress")),
                        isSpam = c.getInt(c.getColumnIndexOrThrow("isSpam")) == 1,
                        score = c.getDouble(c.getColumnIndexOrThrow("score")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
                        userLabel = when {
                            c.isNull(c.getColumnIndexOrThrow("userLabel")) -> null
                            else -> c.getInt(c.getColumnIndexOrThrow("userLabel")) == 1
                        }
                    )
                )
            }
        }
        return list
    }
    override fun observeAll(): Flow<List<MessageVerdictEntity>> = flow
    override suspend fun insert(entity: MessageVerdictEntity) = withContext(Dispatchers.IO) {
        val v = android.content.ContentValues().apply {
            put("messageId", entity.messageId)
            put("threadId", entity.threadId)
            put("normalizedAddress", entity.normalizedAddress)
            put("isSpam", if (entity.isSpam) 1 else 0)
            put("score", entity.score)
            put("createdAt", entity.createdAt)
            if (entity.userLabel == null) putNull("userLabel") else put("userLabel", if (entity.userLabel) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict("message_verdict", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        flow.value = readAllSync()
        Unit
    }
    override suspend fun getByMessageId(messageId: Long): MessageVerdictEntity? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("message_verdict", null, "messageId = ?", arrayOf(messageId.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) MessageVerdictEntity(
                messageId = c.getLong(c.getColumnIndexOrThrow("messageId")),
                threadId = c.getLong(c.getColumnIndexOrThrow("threadId")),
                normalizedAddress = c.getString(c.getColumnIndexOrThrow("normalizedAddress")),
                isSpam = c.getInt(c.getColumnIndexOrThrow("isSpam")) == 1,
                score = c.getDouble(c.getColumnIndexOrThrow("score")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
                userLabel = if (c.isNull(c.getColumnIndexOrThrow("userLabel"))) null else c.getInt(c.getColumnIndexOrThrow("userLabel")) == 1
            ) else null
        }
    }
    override suspend fun getByThread(threadId: Long): List<MessageVerdictEntity> = withContext(Dispatchers.IO){
        val list = mutableListOf<MessageVerdictEntity>()
        helper.readableDatabase.query("message_verdict", null, "threadId = ?", arrayOf(threadId.toString()), null, null, null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    MessageVerdictEntity(
                        messageId = c.getLong(c.getColumnIndexOrThrow("messageId")),
                        threadId = c.getLong(c.getColumnIndexOrThrow("threadId")),
                        normalizedAddress = c.getString(c.getColumnIndexOrThrow("normalizedAddress")),
                        isSpam = c.getInt(c.getColumnIndexOrThrow("isSpam")) == 1,
                        score = c.getDouble(c.getColumnIndexOrThrow("score")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
                        userLabel = if (c.isNull(c.getColumnIndexOrThrow("userLabel"))) null else c.getInt(c.getColumnIndexOrThrow("userLabel")) == 1
                    )
                )
            }
        }
        list
    }
    override suspend fun deleteByThread(threadId: Long) { withContext(Dispatchers.IO){ helper.writableDatabase.delete("message_verdict","threadId = ?", arrayOf(threadId.toString())); flow.value = readAllSync() } }
    override suspend fun deleteAutoOlderThan(cutoffMillis: Long): Int = withContext(Dispatchers.IO){
        val r = helper.writableDatabase.delete("message_verdict","userLabel IS NULL AND createdAt < ?", arrayOf(cutoffMillis.toString()))
        if (r>0) flow.value = readAllSync()
        r
    }
    override suspend fun updateUserLabel(messageId: Long, userLabel: Boolean?) { withContext(Dispatchers.IO){
        val v = android.content.ContentValues().apply { if (userLabel==null) putNull("userLabel") else put("userLabel", if(userLabel)1 else 0) }
        helper.writableDatabase.update("message_verdict", v, "messageId = ?", arrayOf(messageId.toString()))
        flow.value = readAllSync()
    }}
}

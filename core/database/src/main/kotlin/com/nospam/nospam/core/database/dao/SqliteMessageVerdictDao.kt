package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteMessageVerdictDao(private val helper: SqliteNoSpamOpenHelper) : MessageVerdictDao {
    private val flow = MutableStateFlow<List<MessageVerdictEntity>>(emptyList())
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
    private fun publish(delta: (List<MessageVerdictEntity>) -> List<MessageVerdictEntity>) {
        if (loaded) {
            flow.value = delta(flow.value)
            initialized.set(true)
        } else {
            load()
        }
    }
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
    override fun observeAll(): Flow<List<MessageVerdictEntity>> = flow.onStart {
        if (initialized.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                writeLock.withLock { if (!loaded) load() }
            }
        }
    }
    override suspend fun insert(entity: MessageVerdictEntity) = withContext(Dispatchers.IO) { writeLock.withLock {
        insertEntity(entity)
        publish { it.withVerdict(entity) }
        Unit
    } }
    override suspend fun insertAll(entities: List<MessageVerdictEntity>) = withContext(Dispatchers.IO) { writeLock.withLock {
        if (entities.isEmpty()) return@withContext
        helper.writableDatabase.beginTransaction()
        try {
            entities.forEach { insertEntity(it) }
            helper.writableDatabase.setTransactionSuccessful()
        } finally {
            helper.writableDatabase.endTransaction()
        }
        publish { it.withVerdicts(entities) }
    } }

    private fun insertEntity(entity: MessageVerdictEntity) {
        val v = messageVerdictValues(entity)
        helper.writableDatabase.insertWithOnConflict("message_verdict", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun messageVerdictValues(entity: MessageVerdictEntity) = android.content.ContentValues().apply {
        put("messageId", entity.messageId)
        put("threadId", entity.threadId)
        put("normalizedAddress", entity.normalizedAddress)
        put("isSpam", if (entity.isSpam) 1 else 0)
        put("score", entity.score)
        put("createdAt", entity.createdAt)
        if (entity.userLabel == null) putNull("userLabel") else put("userLabel", if (entity.userLabel) 1 else 0)
    }

    override suspend fun getAllMessageIds(): Set<Long> = withContext(Dispatchers.IO) {
        val ids = mutableSetOf<Long>()
        helper.readableDatabase.query("message_verdict", arrayOf("messageId"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        ids
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
    override suspend fun deleteByThread(threadId: Long) { withContext(Dispatchers.IO){ writeLock.withLock { helper.writableDatabase.delete("message_verdict","threadId = ?", arrayOf(threadId.toString())); publish { it.withoutThread(threadId) } } } }
    override suspend fun deleteAutoOlderThan(cutoffMillis: Long): Int = withContext(Dispatchers.IO){ writeLock.withLock {
        val r = helper.writableDatabase.delete("message_verdict","userLabel IS NULL AND isSpam = 0 AND createdAt < ?", arrayOf(cutoffMillis.toString()))
        if (r>0) publish { it.prunedAutoHamBefore(cutoffMillis) }
        r
    } }
    override suspend fun updateUserLabel(messageId: Long, userLabel: Boolean?) { withContext(Dispatchers.IO){ writeLock.withLock {
        val v = android.content.ContentValues().apply { if (userLabel==null) putNull("userLabel") else put("userLabel", if(userLabel)1 else 0) }
        helper.writableDatabase.update("message_verdict", v, "messageId = ?", arrayOf(messageId.toString()))
        publish { it.withUserLabel(messageId, userLabel) }
    } }}
}

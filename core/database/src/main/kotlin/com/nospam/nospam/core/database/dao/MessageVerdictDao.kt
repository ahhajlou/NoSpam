// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface MessageVerdictDao {
    suspend fun insert(entity: MessageVerdictEntity)
    /** One atomic bulk insert; refreshes the observable flow once at the end. */
    suspend fun insertAll(entities: List<MessageVerdictEntity>)
    /** All classified message ids — used by backfill resume-skip checks. */
    suspend fun getAllMessageIds(): Set<Long>
    suspend fun getByMessageId(messageId: Long): MessageVerdictEntity?
    fun observeAll(): Flow<List<MessageVerdictEntity>>
    suspend fun getByThread(threadId: Long): List<MessageVerdictEntity>
    suspend fun deleteByThread(threadId: Long)
    suspend fun deleteAutoOlderThan(cutoffMillis: Long): Int
    suspend fun updateUserLabel(messageId: Long, userLabel: Boolean?)
}

class InMemoryMessageVerdictDao : MessageVerdictDao {
    private val data = mutableMapOf<Long, MessageVerdictEntity>()
    private val flow = MutableStateFlow<List<MessageVerdictEntity>>(emptyList())
    private fun refresh() { flow.value = data.values.toList() }
    override suspend fun insert(entity: MessageVerdictEntity) { data[entity.messageId] = entity; refresh() }
    override suspend fun insertAll(entities: List<MessageVerdictEntity>) {
        if (entities.isEmpty()) return
        entities.forEach { data[it.messageId] = it }
        refresh()
    }
    override suspend fun getAllMessageIds(): Set<Long> = data.keys.toSet()
    override suspend fun getByMessageId(messageId: Long) = data[messageId]
    override fun observeAll(): Flow<List<MessageVerdictEntity>> = flow
    override suspend fun getByThread(threadId: Long) = data.values.filter { it.threadId == threadId }
    override suspend fun deleteByThread(threadId: Long) { data.entries.removeIf { it.value.threadId == threadId }; refresh() }
    override suspend fun deleteAutoOlderThan(cutoffMillis: Long): Int {
        var r = 0
        val it = data.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.userLabel == null && !e.isSpam && e.createdAt < cutoffMillis) { it.remove(); r++ }
        }
        if (r > 0) refresh()
        return r
    }
    override suspend fun updateUserLabel(messageId: Long, userLabel: Boolean?) {
        data[messageId]?.let { data[messageId] = it.copy(userLabel = userLabel); refresh() }
    }
}

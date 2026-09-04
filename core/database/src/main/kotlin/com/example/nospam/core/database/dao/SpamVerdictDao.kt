package com.example.nospam.core.database.dao

import com.example.nospam.core.database.entity.SpamVerdictEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface SpamVerdictDao {
    suspend fun getByThread(threadId: Long): SpamVerdictEntity?
    fun observeSpam(): Flow<List<SpamVerdictEntity>>
    suspend fun upsert(entity: SpamVerdictEntity)
    suspend fun deleteByThread(threadId: Long)
    suspend fun clearAutoSpam()
    /** Deletes auto (non-override) spam verdicts older than [cutoffMillis]. Returns rows removed. */
    suspend fun deleteAutoSpamOlderThan(cutoffMillis: Long): Int
}

class InMemorySpamVerdictDao : SpamVerdictDao {
    private val data = mutableMapOf<Long, SpamVerdictEntity>()
    private val flow = MutableStateFlow<List<SpamVerdictEntity>>(emptyList())
    private fun refresh() { flow.value = data.values.filter { it.isSpam } }
    override suspend fun getByThread(threadId: Long): SpamVerdictEntity? = data[threadId]
    override fun observeSpam(): Flow<List<SpamVerdictEntity>> = flow
    override suspend fun upsert(entity: SpamVerdictEntity) { data[entity.threadId] = entity; refresh() }
    override suspend fun deleteByThread(threadId: Long) { data.remove(threadId); refresh() }
    override suspend fun clearAutoSpam() { data.entries.removeIf { it.value.isSpam && !it.value.isUserOverride }; refresh() }
    override suspend fun deleteAutoSpamOlderThan(cutoffMillis: Long): Int {
        var removed = 0
        val it = data.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.isSpam && !e.isUserOverride && e.updatedAt < cutoffMillis) {
                it.remove()
                removed++
            }
        }
        if (removed > 0) refresh()
        return removed
    }
}

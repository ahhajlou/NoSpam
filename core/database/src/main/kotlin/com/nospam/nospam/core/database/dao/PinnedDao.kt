package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.PinnedThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface PinnedDao {
    fun observeAll(): Flow<List<PinnedThreadEntity>>
    suspend fun pin(threadId: Long)
    suspend fun unpin(threadId: Long)
    suspend fun isPinned(threadId: Long): Boolean
}

class InMemoryPinnedDao : PinnedDao {
    private val data = mutableSetOf<Long>()
    private val flow = MutableStateFlow<List<PinnedThreadEntity>>(emptyList())
    private fun refresh() { flow.value = data.map { PinnedThreadEntity(it) } }
    override fun observeAll(): Flow<List<PinnedThreadEntity>> = flow
    override suspend fun pin(threadId: Long) { data.add(threadId); refresh() }
    override suspend fun unpin(threadId: Long) { data.remove(threadId); refresh() }
    override suspend fun isPinned(threadId: Long) = threadId in data
}

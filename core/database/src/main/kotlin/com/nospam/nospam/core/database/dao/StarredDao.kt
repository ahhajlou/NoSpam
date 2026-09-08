package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.StarredThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface StarredDao {
    fun observeAll(): Flow<List<StarredThreadEntity>>
    suspend fun star(threadId: Long)
    suspend fun unstar(threadId: Long)
    suspend fun isStarred(threadId: Long): Boolean
}

class InMemoryStarredDao : StarredDao {
    private val data = mutableSetOf<Long>()
    private val flow = MutableStateFlow<List<StarredThreadEntity>>(emptyList())
    private fun refresh() { flow.value = data.map { StarredThreadEntity(it) } }
    override fun observeAll(): Flow<List<StarredThreadEntity>> = flow
    override suspend fun star(threadId: Long) { data.add(threadId); refresh() }
    override suspend fun unstar(threadId: Long) { data.remove(threadId); refresh() }
    override suspend fun isStarred(threadId: Long) = threadId in data
}

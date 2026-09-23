// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.StarredThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface StarredDao {
    fun observeAll(): Flow<List<StarredThreadEntity>>
    suspend fun star(threadId: Long)
    suspend fun unstar(threadId: Long)
    /** Stars every thread in [threadIds] in one write. */
    suspend fun starAll(threadIds: Collection<Long>)
    /** Unstars every thread in [threadIds] in one write. */
    suspend fun unstarAll(threadIds: Collection<Long>)
    suspend fun isStarred(threadId: Long): Boolean
}

class InMemoryStarredDao : StarredDao {
    private val data = mutableSetOf<Long>()
    private val flow = MutableStateFlow<List<StarredThreadEntity>>(emptyList())
    private fun refresh() { flow.value = data.map { StarredThreadEntity(it) } }
    override fun observeAll(): Flow<List<StarredThreadEntity>> = flow
    override suspend fun star(threadId: Long) { data.add(threadId); refresh() }
    override suspend fun unstar(threadId: Long) { data.remove(threadId); refresh() }
    override suspend fun starAll(threadIds: Collection<Long>) { data.addAll(threadIds); refresh() }
    override suspend fun unstarAll(threadIds: Collection<Long>) { data.removeAll(threadIds.toSet()); refresh() }
    override suspend fun isStarred(threadId: Long) = threadId in data
}

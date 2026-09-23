// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.MutedThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface MutedDao {
    fun observeAll(): Flow<List<MutedThreadEntity>>
    suspend fun mute(threadId: Long)
    suspend fun unmute(threadId: Long)
    /** Mutes every thread in [threadIds] in one write. */
    suspend fun muteAll(threadIds: Collection<Long>)
    /** Unmutes every thread in [threadIds] in one write. */
    suspend fun unmuteAll(threadIds: Collection<Long>)
    suspend fun isMuted(threadId: Long): Boolean
}

class InMemoryMutedDao : MutedDao {
    private val data = mutableSetOf<Long>()
    private val flow = MutableStateFlow<List<MutedThreadEntity>>(emptyList())
    private fun refresh() { flow.value = data.map { MutedThreadEntity(it) } }
    override fun observeAll(): Flow<List<MutedThreadEntity>> = flow
    override suspend fun mute(threadId: Long) { data.add(threadId); refresh() }
    override suspend fun unmute(threadId: Long) { data.remove(threadId); refresh() }
    override suspend fun muteAll(threadIds: Collection<Long>) { data.addAll(threadIds); refresh() }
    override suspend fun unmuteAll(threadIds: Collection<Long>) { data.removeAll(threadIds.toSet()); refresh() }
    override suspend fun isMuted(threadId: Long) = threadId in data
}

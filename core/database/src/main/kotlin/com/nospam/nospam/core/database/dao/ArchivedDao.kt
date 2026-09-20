// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.ArchivedThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface ArchivedDao {
    fun observeAll(): Flow<List<ArchivedThreadEntity>>
    suspend fun archive(threadId: Long)
    suspend fun unarchive(threadId: Long)
    suspend fun isArchived(threadId: Long): Boolean
}

class InMemoryArchivedDao : ArchivedDao {
    private val data = mutableSetOf<Long>()
    private val flow = MutableStateFlow<List<ArchivedThreadEntity>>(emptyList())
    private fun refresh() {
        flow.value = data.map { ArchivedThreadEntity(it) }
    }
    override fun observeAll(): Flow<List<ArchivedThreadEntity>> = flow
    override suspend fun archive(threadId: Long) {
        data.add(threadId)
        refresh()
    }
    override suspend fun unarchive(threadId: Long) {
        data.remove(threadId)
        refresh()
    }
    override suspend fun isArchived(threadId: Long): Boolean = threadId in data
}

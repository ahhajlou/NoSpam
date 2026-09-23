// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.BlocklistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface BlocklistDao {
    fun observeAll(): Flow<List<BlocklistEntity>>
    suspend fun findByAddress(address: String): BlocklistEntity?
    suspend fun insert(entry: BlocklistEntity): Long
    suspend fun delete(entry: BlocklistEntity)
    suspend fun deleteByAddress(address: String)
}

class InMemoryBlocklistDao : BlocklistDao {
    private val data = mutableListOf<BlocklistEntity>()
    private val flow = MutableStateFlow<List<BlocklistEntity>>(emptyList())
    private var nextId = 1L
    // Newest block first, as SqliteBlocklistDao's ORDER BY createdAt DESC:
    // tests of anything that relies on that order run against this class.
    private fun publish() { flow.value = data.sortedByDescending { it.createdAt } }
    override fun observeAll(): Flow<List<BlocklistEntity>> = flow
    override suspend fun findByAddress(address: String): BlocklistEntity? = data.find { it.address == address }
    override suspend fun insert(entry: BlocklistEntity): Long {
        val id = if (entry.id == 0L) nextId++ else entry.id
        val e = entry.copy(id = id)
        data.removeAll { it.address == entry.address }
        data.add(e)
        publish()
        return id
    }
    override suspend fun delete(entry: BlocklistEntity) { data.removeIf { it.id == entry.id }; publish() }
    override suspend fun deleteByAddress(address: String) { data.removeIf { it.address == address }; publish() }
}

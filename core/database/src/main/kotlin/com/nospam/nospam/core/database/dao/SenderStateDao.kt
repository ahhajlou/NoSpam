// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.SenderStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface SenderStateDao {
    suspend fun getByAddress(normalizedAddress: String): SenderStateEntity?
    fun observeAll(): Flow<List<SenderStateEntity>>
    /** Full snapshot — backfill preloads the current state map from this. */
    suspend fun getAll(): List<SenderStateEntity>
    suspend fun upsert(entity: SenderStateEntity)
    /** One atomic bulk write; refreshes the observable flow once at the end. */
    suspend fun upsertAll(entities: List<SenderStateEntity>)
    suspend fun deleteByAddress(normalizedAddress: String)
}

class InMemorySenderStateDao : SenderStateDao {
    private val data = mutableMapOf<String, SenderStateEntity>()
    private val flow = MutableStateFlow<List<SenderStateEntity>>(emptyList())
    private fun refresh() { flow.value = data.values.toList() }
    override suspend fun getByAddress(normalizedAddress: String) = data[normalizedAddress]
    override fun observeAll(): Flow<List<SenderStateEntity>> = flow
    override suspend fun getAll(): List<SenderStateEntity> = data.values.toList()
    override suspend fun upsert(entity: SenderStateEntity) { data[entity.normalizedAddress] = entity; refresh() }
    override suspend fun upsertAll(entities: List<SenderStateEntity>) {
        if (entities.isEmpty()) return
        entities.forEach { data[it.normalizedAddress] = it }
        refresh()
    }
    override suspend fun deleteByAddress(normalizedAddress: String) { data.remove(normalizedAddress); refresh() }
}

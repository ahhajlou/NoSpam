package com.example.nospam.core.database.dao

import com.example.nospam.core.database.entity.ModelMetadataEntity

interface ModelMetadataDao {
    suspend fun get(): ModelMetadataEntity?
    suspend fun upsert(entity: ModelMetadataEntity)
}

class InMemoryModelMetadataDao : ModelMetadataDao {
    private var value: ModelMetadataEntity? = null
    override suspend fun get(): ModelMetadataEntity? = value
    override suspend fun upsert(entity: ModelMetadataEntity) { value = entity }
}

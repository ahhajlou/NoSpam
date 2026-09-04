package com.example.nospam.core.database.entity

data class ModelMetadataEntity(
    val id: Int = 1,
    val version: String,
    val threshold: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis()
)

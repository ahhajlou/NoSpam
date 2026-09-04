package com.example.nospam.core.database.entity

data class SpamVerdictEntity(
    val threadId: Long,
    val isSpam: Boolean,
    val score: Double,
    val isUserOverride: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

package com.nospam.nospam.core.database.entity

data class BlocklistEntity(
    val id: Long = 0,
    val address: String,
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

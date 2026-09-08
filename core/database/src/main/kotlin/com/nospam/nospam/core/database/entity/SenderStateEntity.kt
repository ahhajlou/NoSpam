package com.nospam.nospam.core.database.entity

import com.nospam.nospam.core.model.ThreadSpamState

data class SenderStateEntity(
    val normalizedAddress: String,
    val state: ThreadSpamState,
    val spamCount: Int = 0,
    val hamCount: Int = 0,
    val isUserOverride: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.entity

data class MessageVerdictEntity(
    val messageId: Long,
    val threadId: Long,
    val normalizedAddress: String,
    val isSpam: Boolean,
    val score: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val userLabel: Boolean? = null,
)

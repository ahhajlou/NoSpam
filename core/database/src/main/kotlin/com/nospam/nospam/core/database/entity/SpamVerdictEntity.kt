// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.entity

data class SpamVerdictEntity(
    val threadId: Long,
    val isSpam: Boolean,
    val score: Double,
    val isUserOverride: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

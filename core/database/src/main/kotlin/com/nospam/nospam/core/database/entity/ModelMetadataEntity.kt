// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.entity

data class ModelMetadataEntity(
    val id: Int = 1,
    val version: String,
    val threshold: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis()
)

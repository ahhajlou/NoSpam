package com.example.nospam.core.database.entity

/**
 * App-owned archive flags. The Telephony provider has no archived column,
 * so archived thread ids live here (same pattern as spam verdicts).
 */
data class ArchivedThreadEntity(
    val threadId: Long,
    val archivedAt: Long = System.currentTimeMillis()
)

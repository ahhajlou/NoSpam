package com.nospam.nospam.core.model

/**
 * Pure Kotlin domain types — zero Android imports.
 * This module is `kotlin("jvm")`, compiler enforces no Android deps.
 */

@JvmInline
value class ThreadId(val value: Long)

@JvmInline
value class MessageId(val value: Long)

enum class MessageType {
    INBOX, SENT, DRAFT, OUTBOX, FAILED, QUEUED
}

enum class SpamLabel {
    SPAM, HAM
}

data class Participant(
    val address: String,
    val displayName: String? = null,
    val contactId: Long? = null,
    val photoUri: String? = null,
    val isStarred: Boolean = false,
)

data class Message(
    val id: MessageId,
    val threadId: ThreadId,
    val address: String,
    val body: String,
    val date: Long, // epoch millis
    val type: MessageType,
    val read: Boolean,
    val seen: Boolean = false,
    val subscriptionId: Int? = null,
)

data class Conversation(
    val threadId: ThreadId,
    val participants: List<Participant>,
    val snippet: String,
    val date: Long,
    val messageCount: Int,
    val read: Boolean,
    val isArchived: Boolean = false,
    val isSpam: Boolean = false,
    val isBlocked: Boolean = false,
    val isStarred: Boolean = false,
    val isPinned: Boolean = false,
    val hasDraft: Boolean = false,
    val photoUri: String? = null,
)

data class RawMessage(
    val sender: String?,
    val body: String,
    val timestamp: Long,
    val subscriptionId: Int? = null,
)

data class SpamVerdict(
    val label: SpamLabel,
    val score: Double,
    val isSpam: Boolean = label == SpamLabel.SPAM,
    val modelVersion: String? = null,
)

data class BlocklistEntry(
    val id: Long = 0,
    val address: String,
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class SpamOverride(
    val threadId: ThreadId,
    val isNotSpam: Boolean, // true = user marked "Not spam"
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ModelMetadata(
    val version: String,
    val threshold: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class ConversationFilter {
    ALL, UNREAD, KNOWN, UNKNOWN, STARRED
}

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.ArchivedThreadEntity
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.MutedThreadEntity
import com.nospam.nospam.core.database.entity.PinnedThreadEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.database.entity.StarredThreadEntity

/**
 * Pure transforms mirroring each `Sqlite*Dao` write, so a write can update its
 * observable snapshot in place instead of re-reading the whole table.
 *
 * The DAOs used to end every write with `flow.value = readAllSync()`. Once that
 * pair was put under the DAO's write lock to fix the lost-update race, the full
 * re-read became the length of the critical section, so concurrent writers
 * queued behind an O(rows) scan. On the ingress path that ran up to three times
 * per received SMS, against a `message_verdict` table that grows without bound
 * because auto-spam rows are kept forever (CLAUDE.md §15).
 *
 * Each function must mirror the SQL it replaces, **including its ORDER BY** —
 * the accompanying comment names the statement it tracks. This file has no
 * Android imports on purpose: the DAOs need `android.database` and so cannot be
 * unit-tested off-device, but everything here can, and is, in `FlowDeltasTest`.
 *
 * A delta is only valid once the flow already holds a full snapshot. Each DAO
 * tracks that with a `loaded` flag guarded by its write lock and falls back to a
 * full read when it is not set.
 */

// ---- blocklist: ORDER BY createdAt DESC, address is UNIQUE ----

/** `INSERT ... ON CONFLICT REPLACE` keyed on the unique `address`. */
internal fun List<BlocklistEntity>.withEntry(entry: BlocklistEntity): List<BlocklistEntity> =
    (filterNot { it.address == entry.address } + entry).sortedByDescending { it.createdAt }

/** `DELETE FROM blocklist WHERE id = ?` */
internal fun List<BlocklistEntity>.withoutId(id: Long): List<BlocklistEntity> =
    filterNot { it.id == id }

/** `DELETE FROM blocklist WHERE address = ?` */
@JvmName("blocklistWithoutAddress")
internal fun List<BlocklistEntity>.withoutAddress(address: String): List<BlocklistEntity> =
    filterNot { it.address == address }

// ---- message_verdict: ORDER BY createdAt DESC, messageId is the primary key ----

/** `INSERT ... ON CONFLICT REPLACE` keyed on `messageId`. */
internal fun List<MessageVerdictEntity>.withVerdict(
    entity: MessageVerdictEntity,
): List<MessageVerdictEntity> = withVerdicts(listOf(entity))

/** The batched form, one transaction of the same upsert. */
internal fun List<MessageVerdictEntity>.withVerdicts(
    entities: List<MessageVerdictEntity>,
): List<MessageVerdictEntity> {
    if (entities.isEmpty()) return this
    val replaced = entities.mapTo(HashSet()) { it.messageId }
    return (filterNot { it.messageId in replaced } + entities)
        .sortedByDescending { it.createdAt }
}

/** `DELETE FROM message_verdict WHERE threadId = ?` */
@JvmName("verdictsWithoutThread")
internal fun List<MessageVerdictEntity>.withoutThread(threadId: Long): List<MessageVerdictEntity> =
    filterNot { it.threadId == threadId }

/**
 * `DELETE FROM message_verdict WHERE userLabel IS NULL AND isSpam = 0 AND createdAt < ?`
 *
 * Auto-classified ham only: a user-labelled row, or any spam row, is retained
 * regardless of age, because spam rows are the per-message evidence behind the
 * "Suspected spam" marker (CLAUDE.md §15).
 */
internal fun List<MessageVerdictEntity>.prunedAutoHamBefore(
    cutoffMillis: Long,
): List<MessageVerdictEntity> =
    filterNot { it.userLabel == null && !it.isSpam && it.createdAt < cutoffMillis }

/** `UPDATE message_verdict SET userLabel = ? WHERE messageId = ?` */
internal fun List<MessageVerdictEntity>.withUserLabel(
    messageId: Long,
    userLabel: Boolean?,
): List<MessageVerdictEntity> =
    map { if (it.messageId == messageId) it.copy(userLabel = userLabel) else it }

// ---- sender_state: no ORDER BY, normalizedAddress is the primary key ----

/** `INSERT ... ON CONFLICT REPLACE` keyed on `normalizedAddress`. */
internal fun List<SenderStateEntity>.withState(entity: SenderStateEntity): List<SenderStateEntity> =
    withStates(listOf(entity))

/** The batched form, one transaction of the same upsert. */
internal fun List<SenderStateEntity>.withStates(
    entities: List<SenderStateEntity>,
): List<SenderStateEntity> {
    if (entities.isEmpty()) return this
    val replaced = entities.mapTo(HashSet()) { it.normalizedAddress }
    return filterNot { it.normalizedAddress in replaced } + entities
}

/** `DELETE FROM sender_state WHERE normalizedAddress = ?` */
@JvmName("senderStateWithoutAddress")
internal fun List<SenderStateEntity>.withoutAddress(address: String): List<SenderStateEntity> =
    filterNot { it.normalizedAddress == address }

// ---- spam_verdict: the flow holds only `isSpam = 1` rows (see readSpamSync) ----

/**
 * `INSERT ... ON CONFLICT REPLACE` keyed on `threadId`. Because the observed
 * snapshot is filtered to spam, a row that is upserted as ham leaves the list.
 */
internal fun List<SpamVerdictEntity>.withSpamVerdict(
    entity: SpamVerdictEntity,
): List<SpamVerdictEntity> {
    val rest = filterNot { it.threadId == entity.threadId }
    return if (entity.isSpam) rest + entity else rest
}

/** `DELETE FROM spam_verdict WHERE threadId = ?` */
@JvmName("spamVerdictsWithoutThread")
internal fun List<SpamVerdictEntity>.withoutThread(threadId: Long): List<SpamVerdictEntity> =
    filterNot { it.threadId == threadId }

/**
 * `DELETE FROM spam_verdict WHERE isSpam = 1 AND isUserOverride = 0`
 *
 * The `isSpam = 1` half is already true of everything in the snapshot.
 */
internal fun List<SpamVerdictEntity>.withoutAutoSpam(): List<SpamVerdictEntity> =
    filter { it.isUserOverride }

/** `DELETE FROM spam_verdict WHERE isSpam = 1 AND isUserOverride = 0 AND updatedAt < ?` */
internal fun List<SpamVerdictEntity>.withoutAutoSpamBefore(
    cutoffMillis: Long,
): List<SpamVerdictEntity> =
    filterNot { !it.isUserOverride && it.updatedAt < cutoffMillis }

// ---- threadId-keyed flag tables: no ORDER BY, INSERT uses CONFLICT_IGNORE ----

@JvmName("archivedWithThread")
internal fun List<ArchivedThreadEntity>.withThread(threadId: Long): List<ArchivedThreadEntity> =
    if (any { it.threadId == threadId }) this else this + ArchivedThreadEntity(threadId)

@JvmName("archivedWithoutThread")
internal fun List<ArchivedThreadEntity>.withoutThread(threadId: Long): List<ArchivedThreadEntity> =
    filterNot { it.threadId == threadId }

@JvmName("starredWithThread")
internal fun List<StarredThreadEntity>.withThread(threadId: Long): List<StarredThreadEntity> =
    if (any { it.threadId == threadId }) this else this + StarredThreadEntity(threadId)

@JvmName("starredWithoutThread")
internal fun List<StarredThreadEntity>.withoutThread(threadId: Long): List<StarredThreadEntity> =
    filterNot { it.threadId == threadId }

@JvmName("pinnedWithThread")
internal fun List<PinnedThreadEntity>.withThread(threadId: Long): List<PinnedThreadEntity> =
    if (any { it.threadId == threadId }) this else this + PinnedThreadEntity(threadId)

@JvmName("pinnedWithoutThread")
internal fun List<PinnedThreadEntity>.withoutThread(threadId: Long): List<PinnedThreadEntity> =
    filterNot { it.threadId == threadId }

@JvmName("mutedWithThread")
internal fun List<MutedThreadEntity>.withThread(threadId: Long): List<MutedThreadEntity> =
    if (any { it.threadId == threadId }) this else this + MutedThreadEntity(threadId)

@JvmName("mutedWithoutThread")
internal fun List<MutedThreadEntity>.withoutThread(threadId: Long): List<MutedThreadEntity> =
    filterNot { it.threadId == threadId }

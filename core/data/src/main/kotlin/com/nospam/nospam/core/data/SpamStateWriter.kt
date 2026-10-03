// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One backfill state write to apply: [computed] is the state derived purely from
 * the pre-scan snapshot and the historical messages; [seedSpamCount]/[seedHamCount]
 * are that snapshot's counters, so any increments an incoming SMS added *during*
 * the scan can be merged in instead of being clobbered (CLAUDE.md §15).
 */
data class PendingStateWrite(
    val address: String,
    val computed: com.nospam.nospam.core.database.entity.SenderStateEntity,
    val seedSpamCount: Int = 0,
    val seedHamCount: Int = 0,
)

/**
 * Single-writer gate for every `sender_state` read→decide→write path (ingress,
 * backfill, user overrides). Guarantees that auto writes never clobber a user
 * override and that a concurrent backfill and an incoming SMS serialize their
 * non-atomic check-then-write instead of interleaving (CLAUDE.md §15).
 */
class SpamStateWriter(private val senderStateDao: SenderStateDao) {
    private val mutex = Mutex()

    /**
     * Under the lock: no-op and returns null if [address] is currently
     * user-overridden; otherwise computes + upserts a single state row.
     */
    suspend fun upsertIfNotOverridden(
        address: String,
        compute: (SenderStateEntity?) -> SenderStateEntity,
    ): SenderStateEntity? = mutex.withLock {
        val current = senderStateDao.getByAddress(address)
        if (current?.isUserOverride == true) return null
        val updated = compute(current)
        senderStateDao.upsert(updated)
        updated
    }

    /**
     * Under the lock: bulk upsert. Skips addresses with a user override and adds
     * back any counter increments that raced in from new SMS while the scan was
     * running, so the scan's aggregated state never erases live ingress counts.
     * The current rows are read in one query for the whole batch.
     *
     * The two-argument form takes `write`, which stores the result; the history scan passes one that writes its
     * verdicts in the same transaction (`NoSpamDatabase.writeScanBatch`). It is
     * called even when no state needs writing, so those verdicts still land.
     */
    suspend fun upsertAllIfNotOverridden(pending: List<PendingStateWrite>) {
        if (pending.isEmpty()) return
        upsertAllIfNotOverridden(pending) { senderStateDao.upsertAll(it) }
    }

    // An overload, not a default value: a suspend lambda as a parameter default
    // crashes the Kotlin 2.2 JVM backend (AddContinuationLowering).
    suspend fun upsertAllIfNotOverridden(
        pending: List<PendingStateWrite>,
        write: suspend (List<SenderStateEntity>) -> Unit,
    ): Unit = mutex.withLock {
        val currentByAddress = if (pending.isEmpty()) emptyMap() else senderStateDao.getByAddresses(pending.map { it.address })
        val toWrite = pending.mapNotNull { w ->
            val current = currentByAddress[w.address]
            if (current?.isUserOverride == true) null
            else {
                val spamDelta = maxOf(0, (current?.spamCount ?: 0) - w.seedSpamCount)
                val hamDelta = maxOf(0, (current?.hamCount ?: 0) - w.seedHamCount)
                val merged = w.computed.copy(
                    spamCount = w.computed.spamCount + spamDelta,
                    hamCount = w.computed.hamCount + hamDelta,
                    hasReplied = w.computed.hasReplied || current?.hasReplied == true,
                )
                // With a message merged in, the state follows the merged counts.
                // Without one the scan's own state stands: it may be CLEAN on
                // purpose, with spam protection off.
                if (spamDelta + hamDelta > 0 && merged.state in AUTOMATIC_STATES) {
                    merged.copy(state = ThreadSpamPolicy.deriveState(merged.spamCount, merged.hamCount, merged.hasReplied))
                } else {
                    merged
                }
            }
        }
        write(toWrite)
    }

    /** Serializes explicit user-override writes against any running scan. */
    suspend fun <T> withSpamStateLock(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        /** States [ThreadSpamPolicy.deriveState] produces; anything else is a user decision or a block. */
        val AUTOMATIC_STATES = setOf(
            ThreadSpamState.CLEAN,
            ThreadSpamState.MIXED,
            ThreadSpamState.SUSPECTED,
            ThreadSpamState.SPAM,
        )
    }
}
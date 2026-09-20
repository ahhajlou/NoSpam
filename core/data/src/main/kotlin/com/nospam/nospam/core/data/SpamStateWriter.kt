// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.entity.SenderStateEntity
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
     */
    suspend fun upsertAllIfNotOverridden(pending: List<PendingStateWrite>) = mutex.withLock {
        if (pending.isEmpty()) return
        val toWrite = pending.mapNotNull { w ->
            val current = senderStateDao.getByAddress(w.address)
            if (current?.isUserOverride == true) null
            else {
                val spamDelta = maxOf(0, (current?.spamCount ?: 0) - w.seedSpamCount)
                val hamDelta = maxOf(0, (current?.hamCount ?: 0) - w.seedHamCount)
                w.computed.copy(
                    spamCount = w.computed.spamCount + spamDelta,
                    hamCount = w.computed.hamCount + hamDelta,
                )
            }
        }
        senderStateDao.upsertAll(toWrite)
    }

    /** Serializes explicit user-override writes against any running scan. */
    suspend fun <T> withSpamStateLock(block: suspend () -> T): T = mutex.withLock { block() }
}
// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.SenderRekey
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.flow.first

/**
 * Brings stored data in line with the current sender key, once per key scheme.
 *
 * Everything keyed by sender was keyed by `PhoneNumberNormalizer.normalize` when
 * it was written. When that rule changes, as it did on 2026-10-04 so that
 * `5000301630` and `+985000301630` became one sender, the rows written under the
 * old keys are moved to the new ones, and rows that now share a key are merged
 * ([mergeSenderStates]). Without it the fix would reach new messages only.
 *
 * Runs at app start, under the spam-state lock so an arriving message cannot
 * interleave, in one transaction ([NoSpamDatabase.rekeySenders]). The key rule
 * maps its own output to itself, so running again changes nothing: a crash
 * between the repair and saving [scheme] is harmless.
 *
 * @param keyFor the current sender key for a stored key.
 * @param scheme identifies the key rule; the repair runs when the stored one differs.
 */
class SenderKeyRepair(
    private val db: NoSpamDatabase,
    private val spamStateWriter: SpamStateWriter,
    private val keyFor: (String) -> String,
    private val scheme: String,
    private val storedScheme: suspend () -> String?,
    private val saveScheme: suspend (String) -> Unit,
) {
    /** Re-keys when the stored scheme differs; returns whether it ran. */
    suspend fun runIfNeeded(): Boolean {
        if (storedScheme() == scheme) return false
        spamStateWriter.withSpamStateLock {
            val states = db.senderStateDao.getAll()
            val otherKeys = db.messageVerdictDao.observeAll().first().map { it.normalizedAddress } +
                db.blocklistDao.observeAll().first().map { it.address }
            db.rekeySenders(planRekey(states, otherKeys, keyFor))
        }
        saveScheme(scheme)
        return true
    }
}

/**
 * Which stored keys change, and the merged `sender_state` row for every new key
 * that an old key moves to. [otherKeys] are the keys found only in verdicts or
 * the block list.
 */
internal fun planRekey(
    states: List<SenderStateEntity>,
    otherKeys: Collection<String>,
    keyFor: (String) -> String,
): SenderRekey {
    val newKeyFor = (states.map { it.normalizedAddress } + otherKeys).toSet()
        .associateWith(keyFor)
        .filter { (old, new) -> old != new }
    val merged = states
        .groupBy { newKeyFor[it.normalizedAddress] ?: it.normalizedAddress }
        .filterValues { rows -> rows.any { it.normalizedAddress in newKeyFor } }
        .map { (key, rows) -> mergeSenderStates(key, rows) }
    return SenderRekey(newKeyFor, merged)
}

/**
 * One sender's state from rows that were recorded as different senders.
 *
 * Counts add up: every message was counted once, under whichever spelling it
 * arrived with. A reply under any spelling counts. Then, as everywhere else
 * (CLAUDE.md §5), the user's decisions outrank the automatic state:
 * - a block wins, since the block list (re-keyed alongside) blocks the merged key;
 * - otherwise the most recent of "Not spam" and "Report spam" stands;
 * - otherwise the state is derived from the summed counts.
 */
fun mergeSenderStates(key: String, rows: List<SenderStateEntity>): SenderStateEntity {
    require(rows.isNotEmpty()) { "nothing to merge for $key" }
    val spam = rows.sumOf { it.spamCount }
    val ham = rows.sumOf { it.hamCount }
    val replied = rows.any { it.hasReplied }
    val updatedAt = rows.maxOf { it.updatedAt }
    val blocked = rows.filter { it.state == ThreadSpamState.BLOCKED }
    val decision = rows.filter { it.isUserOverride }.maxByOrNull { it.updatedAt }
    val (state, override) = when {
        blocked.isNotEmpty() -> ThreadSpamState.BLOCKED to blocked.any { it.isUserOverride }
        decision != null -> decision.state to true
        else -> ThreadSpamPolicy.deriveState(spam, ham, replied) to false
    }
    return SenderStateEntity(
        normalizedAddress = key,
        state = state,
        spamCount = spam,
        hamCount = ham,
        isUserOverride = override,
        updatedAt = updatedAt,
        hasReplied = replied,
    )
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.content.Context
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SpamRepository(
    private val db: NoSpamDatabase,
    private val classifier: SpamClassifier,
    private val context: Context? = null,
    private val spamStateWriter: SpamStateWriter = SpamStateWriter(db.senderStateDao),
) {
    suspend fun classifyAndStore(threadId: ThreadId, message: RawMessage) {
        val verdict = classifier.classify(message)
        db.spamVerdictDao.upsert(
            SpamVerdictEntity(
                threadId = threadId.value,
                isSpam = verdict.isSpam,
                score = verdict.score,
                isUserOverride = false
            )
        )
    }

    suspend fun markNotSpam(threadId: ThreadId, address: String) = markSenderNotSpam(threadId, address)

    suspend fun markSpam(threadId: ThreadId, address: String) = markSenderSpam(threadId, address)

    suspend fun getVerdict(threadId: ThreadId) = db.spamVerdictDao.getByThread(threadId.value)

    /**
     * User "Not spam" override. Keyed by NORMALIZED address (matches what
     * [SmsIngressUseCase] stores) — never by threadId, which is recycled and
     * caused the inbox/spam-section split (CLAUDE.md §15).
     */
    suspend fun markSenderNotSpam(threadId: ThreadId, address: String) =
        markSendersNotSpam(listOf(threadId to address))

    /** [markSenderNotSpam] for a whole selection, the sender states in one write. */
    suspend fun markSendersNotSpam(conversations: Collection<Pair<ThreadId, String>>) {
        if (conversations.isEmpty()) return
        for ((threadId, _) in conversations) {
            // Legacy per-thread row — kept for export/prune paths, no longer drives lists.
            val existing = db.spamVerdictDao.getByThread(threadId.value)
            if (existing != null) db.spamVerdictDao.upsert(existing.copy(isSpam = false, isUserOverride = true))
            else db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = false, score = 0.0, isUserOverride = true))
        }
        val states = conversations.map { normalizedAddress(it.second) }.distinct().map { addr ->
            com.nospam.nospam.core.database.entity.SenderStateEntity(addr, com.nospam.nospam.core.model.ThreadSpamState.TRUSTED, isUserOverride = true)
        }
        spamStateWriter.withSpamStateLock { db.senderStateDao.upsertAll(states) }
    }

    /** Senders the user marked "Not spam", most recent first, as normalised addresses. */
    fun observeAllowedSenders(): Flow<List<String>> = db.senderStateDao.observeAll().map { states ->
        states
            .filter { it.state == ThreadSpamState.TRUSTED && it.isUserOverride }
            .sortedByDescending { it.updatedAt }
            .map { it.normalizedAddress }
    }

    /**
     * Undoes "Not spam" for [address], so the sender is filtered automatically
     * again. The state before the override is not kept, so it is rebuilt from
     * the stored counts the same way unblocking does: MIXED when the sender has
     * spam history, CLEAN otherwise; the counts themselves are kept. Only a
     * user's TRUSTED override is changed, and no conversation is touched.
     */
    suspend fun removeAllow(address: String) {
        val addr = normalizedAddress(address)
        spamStateWriter.withSpamStateLock {
            val current = db.senderStateDao.getByAddress(addr) ?: return@withSpamStateLock
            if (current.state != ThreadSpamState.TRUSTED || !current.isUserOverride) return@withSpamStateLock
            db.senderStateDao.upsert(
                current.copy(
                    state = if (current.spamCount > 0) ThreadSpamState.MIXED else ThreadSpamState.CLEAN,
                    isUserOverride = false,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun markSenderSpam(threadId: ThreadId, address: String) =
        markSendersSpam(listOf(threadId to address))

    /** The user's "Report spam" for a whole selection, the sender states in one write. */
    suspend fun markSendersSpam(conversations: Collection<Pair<ThreadId, String>>) {
        if (conversations.isEmpty()) return
        // The user put these conversations in Spam; a pin would come back with one
        // if they later say "Not spam" (Google Messages drops it too).
        db.pinnedDao.unpinAll(conversations.map { it.first.value })
        for ((threadId, _) in conversations) {
            db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = true, score = 1.0, isUserOverride = true))
        }
        val states = conversations.map { normalizedAddress(it.second) }.distinct().map { addr ->
            com.nospam.nospam.core.database.entity.SenderStateEntity(addr, com.nospam.nospam.core.model.ThreadSpamState.SPAM, isUserOverride = true, spamCount = 1)
        }
        spamStateWriter.withSpamStateLock { db.senderStateDao.upsertAll(states) }
    }

    // Per-message actions inside MIXED — do not touch sender override
    suspend fun markMessageNotSpam(messageId: Long) {
        spamStateWriter.withSpamStateLock {
            db.messageVerdictDao.updateUserLabel(messageId, false)
            // Recompute counts for that sender (simple: increment ham, recompute state if not override)
            val v = db.messageVerdictDao.getByMessageId(messageId) ?: return@withSpamStateLock
            val state = db.senderStateDao.getByAddress(v.normalizedAddress) ?: return@withSpamStateLock
            if (state.isUserOverride) return@withSpamStateLock
            // increment hamCount, keep state as is unless graduation logic says otherwise (keep MIXED)
            db.senderStateDao.upsert(state.copy(hamCount = state.hamCount + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun markMessageSpam(messageId: Long) {
        spamStateWriter.withSpamStateLock {
            db.messageVerdictDao.updateUserLabel(messageId, true)
            val v = db.messageVerdictDao.getByMessageId(messageId) ?: return@withSpamStateLock
            val state = db.senderStateDao.getByAddress(v.normalizedAddress) ?: return@withSpamStateLock
            if (state.isUserOverride) return@withSpamStateLock
            db.senderStateDao.upsert(state.copy(spamCount = state.spamCount + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Message IDs in [threadId] currently flagged spam — user label wins over the
     * auto verdict (`userLabel ?: isSpam`), matching the export/overlap semantics.
     * Cold-start-first but re-emits on every verdict change, so a per-message
     * "Not spam"/"Report spam" action in the thread flips the UI live.
     */
    fun observeThreadSpamMessageIds(threadId: Long): Flow<Set<Long>> =
        db.messageVerdictDao.observeAll()
            .distinctUntilChanged()
            .map { verdicts ->
                verdicts
                    .filter { it.threadId == threadId && (it.userLabel ?: it.isSpam) }
                    .mapTo(mutableSetOf()) { it.messageId }
            }

    private fun normalizedAddress(address: String): String =
        if (context != null) PhoneNumberNormalizer.normalize(context, address) else address.trim().uppercase()

    /**
     * Retention: drops auto-classified spam verdicts older than [maxAgeDays].
     * User corrections ("not spam" / "report spam") are never pruned.
     */
    suspend fun pruneOldSpam(maxAgeDays: Int = SPAM_RETENTION_DAYS): Int {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1000L
        return db.spamVerdictDao.deleteAutoSpamOlderThan(cutoff)
    }

    companion object {
        const val SPAM_RETENTION_DAYS = 30
    }
}

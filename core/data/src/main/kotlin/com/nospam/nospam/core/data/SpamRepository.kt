// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.content.Context
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamPolicy
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
        val addresses = conversations.map { normalizedAddress(it.second) }.distinct()
        spamStateWriter.withSpamStateLock {
            db.senderStateDao.upsertAll(addresses.map { overridden(it, ThreadSpamState.TRUSTED) })
        }
    }

    /**
     * The user's decision for [address], keeping the sender's counts and reply
     * flag. They are the evidence an automatic state is rebuilt from if the
     * decision is ever undone; overwriting them left an undone "Not spam" only
     * able to return the sender to CLEAN. Caller holds the spam-state lock.
     */
    private suspend fun overridden(address: String, state: ThreadSpamState): SenderStateEntity {
        val current = db.senderStateDao.getByAddress(address)
        return current?.copy(state = state, isUserOverride = true, updatedAt = System.currentTimeMillis())
            ?: SenderStateEntity(address, state, isUserOverride = true)
    }

    /** What the app knows about [address]: its routing state, counts and the user's decision; null if nothing. */
    fun observeSenderState(address: String): Flow<com.nospam.nospam.core.model.SenderState?> {
        val addr = normalizedAddress(address)
        return db.senderStateDao.observeAll()
            .map { states ->
                states.firstOrNull { it.normalizedAddress == addr }?.let {
                    com.nospam.nospam.core.model.SenderState(
                        it.normalizedAddress, it.state, it.spamCount, it.hamCount, it.isUserOverride, it.updatedAt, it.hasReplied,
                    )
                }
            }
            .distinctUntilChanged()
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
     * again. The state is rebuilt from the stored counts by
     * [ThreadSpamPolicy.deriveState], the same way unblocking does. Only a
     * user's TRUSTED override is changed, and no conversation is touched.
     */
    suspend fun removeAllow(address: String) {
        val addr = normalizedAddress(address)
        spamStateWriter.withSpamStateLock {
            val current = db.senderStateDao.getByAddress(addr) ?: return@withSpamStateLock
            if (current.state != ThreadSpamState.TRUSTED || !current.isUserOverride) return@withSpamStateLock
            db.senderStateDao.upsert(
                current.copy(
                    state = ThreadSpamPolicy.deriveState(current.spamCount, current.hamCount, current.hasReplied),
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
        val addresses = conversations.map { normalizedAddress(it.second) }.distinct()
        spamStateWriter.withSpamStateLock {
            db.senderStateDao.upsertAll(addresses.map { overridden(it, ThreadSpamState.SPAM) })
        }
    }

    /**
     * The user's "Not spam" on one message. A message that counted as spam now
     * counts as ham, so a sender whose only spam this was returns to CLEAN, and
     * one that was SUSPECTED or SPAM returns to the inbox. A sender the user has
     * decided about (an override) keeps that decision; only the label changes.
     */
    suspend fun markMessageNotSpam(messageId: Long) = relabel(messageId, isSpam = false)

    /**
     * The user's "Report spam" on one message. It adds spam evidence but takes
     * no ham away: the conversation the user is reading is never hidden by a
     * per-message action, only by "Report spam" on the whole conversation.
     */
    suspend fun markMessageSpam(messageId: Long) = relabel(messageId, isSpam = true)

    private suspend fun relabel(messageId: Long, isSpam: Boolean) {
        spamStateWriter.withSpamStateLock {
            val v = db.messageVerdictDao.getByMessageId(messageId) ?: return@withSpamStateLock
            val wasSpam = v.userLabel ?: v.isSpam
            db.messageVerdictDao.updateUserLabel(messageId, isSpam)
            if (wasSpam == isSpam) return@withSpamStateLock
            val state = db.senderStateDao.getByAddress(v.normalizedAddress) ?: return@withSpamStateLock
            if (state.isUserOverride || state.state !in SpamStateWriter.AUTOMATIC_STATES) return@withSpamStateLock
            val spam = if (isSpam) state.spamCount + 1 else maxOf(0, state.spamCount - 1)
            val ham = if (isSpam) state.hamCount else state.hamCount + 1
            db.senderStateDao.upsert(
                state.copy(
                    spamCount = spam,
                    hamCount = ham,
                    state = ThreadSpamPolicy.deriveState(spam, ham, state.hasReplied),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * A message was deleted. Its verdict goes with it, so it no longer marks the
     * conversation as holding suspected spam. The sender's counts stay: they are
     * the evidence routing rests on, and deleting must not take protection away
     * or make a spam sender look new.
     */
    suspend fun onMessageDeleted(messageId: Long) = db.messageVerdictDao.deleteByMessageId(messageId)

    /**
     * The user sent a message to [address]. A reply clears an automatic spam
     * state (the sender is MIXED at most from now on), and is remembered on the
     * sender so deleting the conversation does not undo it. It never changes the
     * user's own decision: replying "STOP" to a sender they reported or blocked
     * leaves it reported or blocked.
     */
    suspend fun recordReply(address: String) {
        val addr = normalizedAddress(address)
        spamStateWriter.withSpamStateLock {
            val current = db.senderStateDao.getByAddress(addr)
            if (current?.hasReplied == true) return@withSpamStateLock
            val updated = when {
                current == null -> SenderStateEntity(addr, ThreadSpamState.CLEAN, hasReplied = true)
                current.isUserOverride || current.state !in SpamStateWriter.AUTOMATIC_STATES ->
                    current.copy(hasReplied = true)
                else -> current.copy(
                    hasReplied = true,
                    state = ThreadSpamPolicy.deriveState(current.spamCount, current.hamCount, hasReplied = true),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            db.senderStateDao.upsert(updated)
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

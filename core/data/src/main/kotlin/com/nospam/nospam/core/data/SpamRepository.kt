package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.ThreadId

class SpamRepository(
    private val db: NoSpamDatabase,
    private val classifier: SpamClassifier
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

    suspend fun markNotSpam(threadId: ThreadId) = markSenderNotSpam(threadId)

    suspend fun markSpam(threadId: ThreadId) = markSenderSpam(threadId)

    suspend fun getVerdict(threadId: ThreadId) = db.spamVerdictDao.getByThread(threadId.value)

    // New sender-level overrides (TRUSTED / SPAM) keyed by normalized address
    suspend fun markSenderNotSpam(threadId: ThreadId) {
        // Legacy path
        val existing = db.spamVerdictDao.getByThread(threadId.value)
        if (existing != null) db.spamVerdictDao.upsert(existing.copy(isSpam = false, isUserOverride = true))
        else db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = false, score = 0.0, isUserOverride = true))
        // New state: find normalized address via message verdict or fallback to threadId string
        val addr = resolveAddressForThread(threadId) ?: threadId.value.toString()
        db.senderStateDao.upsert(
            com.nospam.nospam.core.database.entity.SenderStateEntity(addr, com.nospam.nospam.core.model.ThreadSpamState.TRUSTED, isUserOverride = true)
        )
    }

    suspend fun markSenderSpam(threadId: ThreadId) {
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = true, score = 1.0, isUserOverride = true))
        val addr = resolveAddressForThread(threadId) ?: threadId.value.toString()
        db.senderStateDao.upsert(
            com.nospam.nospam.core.database.entity.SenderStateEntity(addr, com.nospam.nospam.core.model.ThreadSpamState.SPAM, isUserOverride = true, spamCount = 1)
        )
    }

    // Per-message actions inside MIXED — do not touch sender override
    suspend fun markMessageNotSpam(messageId: Long) {
        db.messageVerdictDao.updateUserLabel(messageId, false)
        // Recompute counts for that sender (simple: increment ham, recompute state if not override)
        val v = db.messageVerdictDao.getByMessageId(messageId) ?: return
        val state = db.senderStateDao.getByAddress(v.normalizedAddress) ?: return
        if (state.isUserOverride) return
        // increment hamCount, keep state as is unless graduation logic says otherwise (keep MIXED)
        db.senderStateDao.upsert(state.copy(hamCount = state.hamCount + 1, updatedAt = System.currentTimeMillis()))
    }

    suspend fun markMessageSpam(messageId: Long) {
        db.messageVerdictDao.updateUserLabel(messageId, true)
        val v = db.messageVerdictDao.getByMessageId(messageId) ?: return
        val state = db.senderStateDao.getByAddress(v.normalizedAddress) ?: return
        if (state.isUserOverride) return
        db.senderStateDao.upsert(state.copy(spamCount = state.spamCount + 1, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun resolveAddressForThread(threadId: ThreadId): String? {
        return db.messageVerdictDao.getByThread(threadId.value).firstOrNull()?.normalizedAddress
    }

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

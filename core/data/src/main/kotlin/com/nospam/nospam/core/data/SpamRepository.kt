package com.nospam.nospam.core.data

import android.content.Context
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer

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
    suspend fun markSenderNotSpam(threadId: ThreadId, address: String) {
        // Legacy per-thread row — kept for export/prune paths, no longer drives lists.
        val existing = db.spamVerdictDao.getByThread(threadId.value)
        if (existing != null) db.spamVerdictDao.upsert(existing.copy(isSpam = false, isUserOverride = true))
        else db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = false, score = 0.0, isUserOverride = true))
        val addr = normalizedAddress(address)
        spamStateWriter.withSpamStateLock {
            db.senderStateDao.upsert(
                com.nospam.nospam.core.database.entity.SenderStateEntity(addr, com.nospam.nospam.core.model.ThreadSpamState.TRUSTED, isUserOverride = true)
            )
        }
    }

    suspend fun markSenderSpam(threadId: ThreadId, address: String) {
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = true, score = 1.0, isUserOverride = true))
        val addr = normalizedAddress(address)
        spamStateWriter.withSpamStateLock {
            db.senderStateDao.upsert(
                com.nospam.nospam.core.database.entity.SenderStateEntity(addr, com.nospam.nospam.core.model.ThreadSpamState.SPAM, isUserOverride = true, spamCount = 1)
            )
        }
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

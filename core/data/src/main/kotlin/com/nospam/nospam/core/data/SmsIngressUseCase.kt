package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource

/**
 * Orchestrates one incoming SMS: classify → resolve thread → insert into the
 * system inbox (READ=1 for spam to suppress heads-up) → store verdict →
 * opportunistic 30-day retention prune.
 *
 * Lives in core:data because it is the only layer allowed to see
 * core:telephony + core:ml + core:database together. Pure logic, no Android
 * imports — fully unit-testable with fakes.
 */
class SmsIngressUseCase(
    private val telephony: TelephonyDataSource,
    private val classifier: SpamClassifier,
    private val db: NoSpamDatabase,
) {
    data class Result(
        val threadId: ThreadId,
        val isSpam: Boolean,
        val score: Double,
        val sender: String,
        val body: String,
        /** Null when the inbox insert itself failed. */
        val messageId: Long?,
    )

    suspend fun handle(message: RawMessage): Result {
        val sender = message.sender ?: "Unknown"
        val verdict = classifier.classify(message)

        val threadId = ThreadId(telephony.getOrCreateThreadId(sender))

        val messageId = telephony.insertInboxMessage(
            address = sender,
            body = message.body,
            date = message.timestamp,
            read = verdict.isSpam,
        )

        if (threadId.value >= 0) {
            db.spamVerdictDao.upsert(
                SpamVerdictEntity(
                    threadId = threadId.value,
                    isSpam = verdict.isSpam,
                    score = verdict.score,
                    isUserOverride = false,
                )
            )
        }

        // Opportunistic retention, no WorkManager needed for v1.
        runCatching { db.spamVerdictDao.deleteAutoSpamOlderThan(retentionCutoff()) }

        return Result(
            threadId = threadId,
            isSpam = verdict.isSpam,
            score = verdict.score,
            sender = sender,
            body = message.body,
            messageId = messageId,
        )
    }

    private fun retentionCutoff(): Long =
        System.currentTimeMillis() - SpamRepository.SPAM_RETENTION_DAYS * 24L * 60L * 60L * 1000L
}

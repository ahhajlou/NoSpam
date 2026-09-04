package com.example.nospam.core.data

import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.database.entity.SpamVerdictEntity
import com.example.nospam.core.model.RawMessage
import com.example.nospam.core.model.SpamLabel
import com.example.nospam.core.ml.SpamClassifier
import com.example.nospam.core.model.ThreadId

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

    suspend fun markNotSpam(threadId: ThreadId) {
        val existing = db.spamVerdictDao.getByThread(threadId.value)
        if (existing != null) {
            db.spamVerdictDao.upsert(existing.copy(isSpam = false, isUserOverride = true))
        } else {
            db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = false, score = 0.0, isUserOverride = true))
        }
    }

    suspend fun markSpam(threadId: ThreadId) {
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = threadId.value, isSpam = true, score = 1.0, isUserOverride = true))
    }

    suspend fun getVerdict(threadId: ThreadId) = db.spamVerdictDao.getByThread(threadId.value)
}

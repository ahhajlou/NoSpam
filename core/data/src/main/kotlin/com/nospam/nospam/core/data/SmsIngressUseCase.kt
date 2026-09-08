package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.NotificationDecision
import com.nospam.nospam.core.model.PolicyInput
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState
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
    private val context: android.content.Context? = null,
    private val isSpamProtectionEnabled: suspend () -> Boolean = { true },
) {
    data class Result(
        val threadId: ThreadId,
        val isSpam: Boolean,
        val score: Double,
        val sender: String,
        val body: String,
        /** Null when the inbox insert itself failed. */
        val messageId: Long?,
        val notificationDecision: NotificationDecision = if (isSpam) NotificationDecision.SILENT else NotificationDecision.NORMAL,
        val senderState: ThreadSpamState? = null,
    )

    suspend fun handle(message: RawMessage): Result {
        val sender = message.sender ?: "Unknown"
        val normalized = context?.let { com.nospam.nospam.core.telephony.PhoneNumberNormalizer.normalize(it, sender) }
            ?: sender.trim().uppercase()

        // Check blocklist first (app DB + system)
        val isBlocked = runCatching {
            val inApp = db.blocklistDao.findByAddress(normalized) != null ||
                (normalized != sender.trim() && db.blocklistDao.findByAddress(sender.trim()) != null)
            inApp || telephony.isSystemBlocked(sender)
        }.getOrDefault(false)

        // 1) Persist first with READ=0 so the message is never lost if classification fails.
        val messageId = telephony.insertInboxMessage(
            address = sender,
            body = message.body,
            date = message.timestamp,
            read = false,
            subscriptionId = message.subscriptionId,
        )

        val threadId = ThreadId(telephony.getOrCreateThreadId(sender))
        val isMuted = runCatching { db.mutedDao.isMuted(threadId.value) }.getOrDefault(false)

        if (isBlocked) {
            if (messageId != null) runCatching { telephony.updateMessageRead(messageId, read = true) }
            // Update sender state to BLOCKED
            val prevBlocked = db.senderStateDao.getByAddress(normalized)
            if (prevBlocked?.isUserOverride != true) {
                db.senderStateDao.upsert(
                    SenderStateEntity(normalized, ThreadSpamState.BLOCKED, isUserOverride = prevBlocked?.isUserOverride ?: false, spamCount = (prevBlocked?.spamCount ?: 0) + 1)
                )
            }
            if (messageId != null) {
                db.messageVerdictDao.insert(
                    MessageVerdictEntity(messageId, threadId.value, normalized, isSpam = true, score = 1.0, createdAt = message.timestamp)
                )
            }
            runCatching { db.spamVerdictDao.deleteAutoSpamOlderThan(retentionCutoff()) }
            runCatching { db.messageVerdictDao.deleteAutoOlderThan(retentionCutoff()) }
            return Result(threadId, isSpam = true, score = 1.0, sender = sender, body = message.body, messageId = messageId, notificationDecision = NotificationDecision.NONE, senderState = ThreadSpamState.BLOCKED)
        }

        // 2) Classify with timeout — failure degrades to "no verdict, leave unread".
        val verdict = runCatching {
            kotlinx.coroutines.withTimeout(8000L) { classifier.classify(message) }
        }.getOrNull()

        var notificationDecision = NotificationDecision.NORMAL
        var newState: ThreadSpamState? = null
        var isSpamForResult = verdict?.isSpam ?: false

        if (verdict != null) {
            // Gather signals for policy
            val isContact = runCatching { telephony.lookupContact(sender)?.displayName != null }.getOrDefault(false)
            val hasOutbound = runCatching { telephony.hasOutboundMessages(threadId) }.getOrDefault(false)
            val prevStateEntity = db.senderStateDao.getByAddress(normalized)
            val prevSenderState = prevStateEntity?.let {
                com.nospam.nospam.core.model.SenderState(it.normalizedAddress, it.state, it.spamCount, it.hamCount, it.isUserOverride, it.updatedAt)
            }

            val policyInput = PolicyInput(
                prevState = prevSenderState,
                isSpam = verdict.isSpam,
                isContact = isContact,
                hasOutbound = hasOutbound,
                isBlocked = false,
            )
            var policyOut = ThreadSpamPolicy.decideWithAddress(normalized, policyInput)
            // Spam protection off: short-circuit to CLEAN/NORMAL but still store verdict
            val spamEnabled = runCatching { isSpamProtectionEnabled() }.getOrDefault(true)
            if (!spamEnabled) {
                policyOut = policyOut.copy(
                    newState = policyOut.newState.copy(state = ThreadSpamState.CLEAN),
                    notification = NotificationDecision.NORMAL
                )
            }
            // Muted conversations are always silent — override spam policy
            if (isMuted) {
                policyOut = policyOut.copy(notification = NotificationDecision.NONE)
            }
            newState = policyOut.newState.state
            notificationDecision = policyOut.notification
            isSpamForResult = verdict.isSpam

            // Upsert SenderState only when not user override (per spec)
            if (prevStateEntity?.isUserOverride != true) {
                db.senderStateDao.upsert(
                    SenderStateEntity(
                        normalizedAddress = normalized,
                        state = policyOut.newState.state,
                        spamCount = policyOut.newState.spamCount,
                        hamCount = policyOut.newState.hamCount,
                        isUserOverride = policyOut.newState.isUserOverride,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }

            // Store per-message verdict (immutable)
            if (messageId != null) {
                db.messageVerdictDao.insert(
                    MessageVerdictEntity(
                        messageId = messageId,
                        threadId = threadId.value,
                        normalizedAddress = normalized,
                        isSpam = verdict.isSpam,
                        score = verdict.score,
                        createdAt = message.timestamp,
                    )
                )
            }

            // Legacy SpamVerdictEntity keep for UI that still reads it — map from new state
            if (threadId.value >= 0) {
                val legacyIsSpam = policyOut.newState.state == ThreadSpamState.SPAM || policyOut.newState.state == ThreadSpamState.BLOCKED
                // Don't overwrite user override legacy entries with auto
                val existingLegacy = db.spamVerdictDao.getByThread(threadId.value)
                if (existingLegacy?.isUserOverride != true) {
                    db.spamVerdictDao.upsert(
                        SpamVerdictEntity(
                            threadId = threadId.value,
                            isSpam = legacyIsSpam,
                            score = verdict.score,
                            isUserOverride = false,
                        )
                    )
                }
            }

            // Apply READ / notification per decision — muted keeps unread but silent
            when (notificationDecision) {
                NotificationDecision.NONE, NotificationDecision.SILENT -> {
                    if (!isMuted && messageId != null) runCatching { telephony.updateMessageRead(messageId, read = true) }
                }
                NotificationDecision.NORMAL -> { /* leave unread */ }
            }
        }
        // If muted and classifier failed (no verdict), still suppress
        if (isMuted) {
            notificationDecision = NotificationDecision.NONE
        }

        // Opportunistic retention
        runCatching { db.spamVerdictDao.deleteAutoSpamOlderThan(retentionCutoff()) }
        runCatching { db.messageVerdictDao.deleteAutoOlderThan(retentionCutoff()) }

        return Result(
            threadId = threadId,
            isSpam = isSpamForResult,
            score = verdict?.score ?: 0.0,
            sender = sender,
            body = message.body,
            messageId = messageId,
            notificationDecision = notificationDecision,
            senderState = newState,
        )
    }

    private fun retentionCutoff(): Long =
        System.currentTimeMillis() - SpamRepository.SPAM_RETENTION_DAYS * 24L * 60L * 60L * 1000L
}

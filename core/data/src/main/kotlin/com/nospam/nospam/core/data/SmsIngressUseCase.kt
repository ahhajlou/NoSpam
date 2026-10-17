// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.NotificationDecision
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.PolicyInput
import com.nospam.nospam.core.model.PolicyOutput
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SenderState
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates one incoming SMS: insert into the system inbox unread → classify
 * within a budget (skipped for saved contacts) → route through [ThreadSpamPolicy]
 * → mark read only what goes to Spam → store verdict. A verdict that misses the
 * budget is recorded when it arrives; the message is announced meanwhile as its
 * sender is routed now.
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
    private val spamStateWriter: SpamStateWriter = SpamStateWriter(db.senderStateDao),
    private val classifyBudgetMillis: Long = CLASSIFY_BUDGET_MS,
) {
    /** Messages [handle] is still working on, late verdicts included: [classifyMissed] leaves them alone. */
    private val inFlight: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val sweepLock = Mutex()

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
        /**
         * The sender's saved contact, so the notification can show its name and
         * photo without a second lookup. Null for a stranger, a blocked sender
         * (not looked up) or when contacts could not be read.
         */
        val contact: Participant? = null,
        /**
         * Set when the verdict missed the budget: completes with the decision the
         * late verdict produced once it is recorded, or null if the classifier
         * failed. The message was announced by [notificationDecision] meanwhile.
         */
        val lateDecision: Deferred<NotificationDecision?>? = null,
    )

    suspend fun handle(message: RawMessage): Result {
        val sender = message.sender ?: "Unknown"

        // 1) Persist first with READ=0, before anything that can be slow on a cold
        // start (number formatting, opening the database, block-list queries). The
        // system drops its own copy of the SMS once the delivery broadcasts
        // complete, and an ANR completes them, so a process killed before this
        // line loses the message for good.
        val messageId = telephony.insertInboxMessage(
            address = sender,
            body = message.body,
            date = message.timestamp,
            read = false,
            subscriptionId = message.subscriptionId,
        )
        messageId?.let(inFlight::add)
        var result: Result? = null
        try {
            return routeInserted(message, sender, messageId).also { result = it }
        } finally {
            // A late verdict releases the message itself once it is recorded.
            if (result?.lateDecision == null) messageId?.let(inFlight::remove)
        }
    }

    private suspend fun routeInserted(message: RawMessage, sender: String, messageId: Long?): Result {
        val normalized = normalize(sender)
        val isBlocked = isBlocked(sender, normalized)

        val threadId = ThreadId(telephony.getOrCreateThreadId(sender))
        val isMuted = runCatching { db.mutedDao.isMuted(threadId.value) }.getOrDefault(false)

        if (isBlocked) {
            recordBlocked(messageId, threadId, normalized, message.timestamp)
            return Result(threadId, isSpam = true, score = 1.0, sender = sender, body = message.body, messageId = messageId, notificationDecision = NotificationDecision.NONE, senderState = ThreadSpamState.BLOCKED)
        }

        // 2) Saved contacts bypass the classifier entirely: no verdict, no label,
        // no change to the sender's state, a normal notification (TODO.md, "Spam
        // routing — agreed model").
        val contact = runCatching { telephony.lookupContact(sender) }.getOrNull()?.takeIf { it.displayName != null }
        val isContact = contact != null

        // 3) Classify within [classifyBudgetMillis]. The classification runs on its
        // own rather than inside the wait, because loading the model blocks a
        // thread and cannot be canceled: a timeout around it only returned once
        // the load finished, which on a cold start was past the broadcast's 10s
        // limit. Now the wait really ends at the budget, and a verdict that
        // arrives later is still recorded below.
        val classification = if (isContact) null else detachedScope().async { classifier.classify(message) }
        val verdict = classification?.let { awaitWithinBudget(it) }

        var notificationDecision: NotificationDecision
        var newState: ThreadSpamState? = null
        var lateDecision: Deferred<NotificationDecision?>? = null
        if (verdict != null) {
            val policyOut = record(verdict, messageId, threadId, normalized, isMuted, message.timestamp)
            newState = policyOut.newState.state
            notificationDecision = policyOut.notification
        } else {
            notificationDecision = if (isContact) NotificationDecision.NORMAL else unclassifiedDecision(normalized)
            if (classification != null && !classification.isCompleted) {
                // Too slow, not failed: record the verdict when it lands, and hand its
                // decision to the caller, which may announce a message kept silent here.
                lateDecision = detachedScope().async {
                    try {
                        val late = runCatching { classification.await() }.getOrNull() ?: return@async null
                        runCatching { record(late, messageId, threadId, normalized, isMuted, message.timestamp).notification }.getOrNull()
                    } finally {
                        messageId?.let(inFlight::remove)
                    }
                }
            }
        }
        // Muted conversations are always silent, verdict or not.
        if (isMuted) {
            notificationDecision = NotificationDecision.NONE
        }

        return Result(
            threadId = threadId,
            isSpam = verdict?.isSpam ?: false,
            score = verdict?.score ?: 0.0,
            sender = sender,
            body = message.body,
            messageId = messageId,
            notificationDecision = notificationDecision,
            senderState = newState,
            contact = contact,
            lateDecision = lateDecision,
        )
    }

    /**
     * Gives a verdict to recent received messages that have none: ingress was
     * killed before recording one (an ANR on a cold start, or the system
     * reclaiming the process before a late verdict landed), or the classifier
     * failed. Each is routed as ingress would have routed it, except that
     * nothing is inserted and nothing is announced: the message is minutes or
     * days old. Saved contacts are skipped, since they have no verdict by design.
     *
     * Looks back [MISSED_WINDOW_MS] only. Ham verdicts are pruned after
     * [SpamRepository.SPAM_RETENTION_DAYS], so an older message without one may
     * well have been counted already, and counting it again would move its
     * sender. Messages younger than [MISSED_GRACE_MS], or still in [handle],
     * are left to ingress for the same reason. One sweep at a time; a sweep
     * already running makes this return 0 at once.
     *
     * Not safe alongside the history scan, which counts the same messages: the
     * caller keeps the two apart. Returns how many messages got a verdict.
     */
    suspend fun classifyMissed(now: Long = System.currentTimeMillis()): Int {
        if (!sweepLock.tryLock()) return 0
        try {
            val missed = telephony.getInboxMessagesSince(now - MISSED_WINDOW_MS).filter {
                it.date <= now - MISSED_GRACE_MS &&
                    it.id.value !in inFlight &&
                    runCatching { db.messageVerdictDao.getByMessageId(it.id.value) == null }.getOrDefault(false)
            }
            val isContact = HashMap<String, Boolean>()
            var recorded = 0
            for (m in missed) {
                val sender = m.address
                val normalized = normalize(sender)
                if (isBlocked(sender, normalized)) {
                    recordBlocked(m.id.value, m.threadId, normalized, m.date)
                    recorded++
                    continue
                }
                val contact = isContact.getOrPut(sender) {
                    runCatching { telephony.lookupContact(sender) }.getOrNull()?.displayName != null
                }
                if (contact) continue
                val verdict = try {
                    classifier.classify(RawMessage(sender, m.body, m.date, m.subscriptionId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: continue
                val isMuted = runCatching { db.mutedDao.isMuted(m.threadId.value) }.getOrDefault(false)
                record(verdict, m.id.value, m.threadId, normalized, isMuted, m.date)
                recorded++
            }
            return recorded
        } finally {
            sweepLock.unlock()
        }
    }

    /**
     * Drops expired automatic verdicts. Housekeeping, not part of announcing a
     * message: the receiver calls it after the notification is posted.
     */
    suspend fun pruneExpiredVerdicts() {
        runCatching { db.spamVerdictDao.deleteAutoSpamOlderThan(retentionCutoff()) }
        runCatching { db.messageVerdictDao.deleteAutoOlderThan(retentionCutoff()) }
    }

    /**
     * Routes a classified message through [ThreadSpamPolicy] and stores the
     * result: the sender's counts and state, the per-message verdict, and the
     * read flag of a message that goes to Spam. Returns the policy's decision,
     * with a muted conversation already made silent.
     */
    private suspend fun record(
        verdict: SpamVerdict,
        messageId: Long?,
        threadId: ThreadId,
        normalized: String,
        isMuted: Boolean,
        timestamp: Long,
    ): PolicyOutput {
        // Gather signals for policy
        val hasOutbound = runCatching { telephony.hasOutboundMessages(threadId) }.getOrDefault(false)
        // Read before taking the lock: a DataStore read should not be held
        // across it, and the answer does not depend on sender state.
        val spamEnabled = runCatching { isSpamProtectionEnabled() }.getOrDefault(true)

        // Read → decide → write runs as one critical section. Reading the
        // previous state outside the lock let two messages from the same
        // sender both start from the same counters and lose one increment,
        // which would put the sender in the wrong state.
        val policyOut = spamStateWriter.withSpamStateLock {
            val current = db.senderStateDao.getByAddress(normalized)
            val policyInput = PolicyInput(
                prevState = current?.toModel(),
                isSpam = verdict.isSpam,
                hasOutbound = hasOutbound,
                isBlocked = false,
            )
            var out = ThreadSpamPolicy.decideWithAddress(normalized, policyInput)
            // Spam protection off: short-circuit to CLEAN/NORMAL but still store verdict
            if (!spamEnabled) {
                out = out.copy(
                    newState = out.newState.copy(state = ThreadSpamState.CLEAN),
                    notification = NotificationDecision.NORMAL
                )
            }
            // Muted conversations are always silent — override spam policy
            if (isMuted) {
                out = out.copy(notification = NotificationDecision.NONE)
            }
            // A user override is never touched by ingress, but the decision is
            // still computed so the READ/notification path below is unchanged.
            if (current?.isUserOverride != true) {
                db.senderStateDao.upsert(
                    SenderStateEntity(
                        normalizedAddress = normalized,
                        state = out.newState.state,
                        spamCount = out.newState.spamCount,
                        hamCount = out.newState.hamCount,
                        isUserOverride = out.newState.isUserOverride,
                        updatedAt = System.currentTimeMillis(),
                        hasReplied = out.newState.hasReplied,
                    )
                )
            }
            out
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
                    createdAt = timestamp,
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

        // A message routed to Spam is marked read; one that stays in the inbox,
        // labeled or not, stays unread so it can still be found. Muted keeps
        // unread but silent.
        when (policyOut.notification) {
            NotificationDecision.NONE -> {
                if (!isMuted && messageId != null) runCatching { telephony.updateMessageRead(messageId, read = true) }
            }
            NotificationDecision.SILENT, NotificationDecision.NORMAL -> { /* leave unread */ }
        }
        return policyOut
    }

    /** No verdict in time: announce as the sender is routed now ([ThreadSpamPolicy.decideUnclassified]). */
    private suspend fun unclassifiedDecision(normalized: String): NotificationDecision {
        if (!runCatching { isSpamProtectionEnabled() }.getOrDefault(true)) return NotificationDecision.NORMAL
        val current = runCatching { db.senderStateDao.getByAddress(normalized) }.getOrNull()
        return ThreadSpamPolicy.decideUnclassified(current?.toModel())
    }

    /** The verdict, or null when the classifier failed or did not answer within the budget. */
    private suspend fun awaitWithinBudget(classification: Deferred<SpamVerdict>): SpamVerdict? =
        try {
            withTimeoutOrNull(classifyBudgetMillis) { classification.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    /**
     * Work that must outlive the caller: the receiver finishes its broadcast as
     * soon as [handle] returns. Same dispatcher as the caller, but not its child.
     */
    private suspend fun detachedScope(): CoroutineScope =
        CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())

    private fun normalize(sender: String): String =
        context?.let { com.nospam.nospam.core.telephony.PhoneNumberNormalizer.normalize(it, sender) }
            ?: sender.trim().uppercase()

    /** On the app's block list (by either key) or the system's. */
    private suspend fun isBlocked(sender: String, normalized: String): Boolean = runCatching {
        val inApp = db.blocklistDao.findByAddress(normalized) != null ||
            (normalized != sender.trim() && db.blocklistDao.findByAddress(sender.trim()) != null)
        inApp || telephony.isSystemBlocked(sender)
    }.getOrDefault(false)

    /** A blocked sender's message: read, counted as spam, sender kept BLOCKED. */
    private suspend fun recordBlocked(messageId: Long?, threadId: ThreadId, normalized: String, timestamp: Long) {
        if (messageId != null) runCatching { telephony.updateMessageRead(messageId, read = true) }
        // Update sender state to BLOCKED — protected against user overrides under the writer lock.
        spamStateWriter.upsertIfNotOverridden(normalized) { current ->
            SenderStateEntity(normalized, ThreadSpamState.BLOCKED, isUserOverride = current?.isUserOverride ?: false, spamCount = (current?.spamCount ?: 0) + 1)
        }
        if (messageId != null) {
            db.messageVerdictDao.insert(
                MessageVerdictEntity(messageId, threadId.value, normalized, isSpam = true, score = 1.0, createdAt = timestamp)
            )
        }
    }

    private fun SenderStateEntity.toModel(): SenderState =
        SenderState(normalizedAddress, state, spamCount, hamCount, isUserOverride, updatedAt, hasReplied)

    private fun retentionCutoff(): Long =
        System.currentTimeMillis() - SpamRepository.SPAM_RETENTION_DAYS * 24L * 60L * 60L * 1000L

    companion object {
        /**
         * How long [handle] waits for a verdict. The whole broadcast has 10s,
         * shared on a cold start with the app's own startup. On a Galaxy A26, a
         * release build's background cold start loads the model 1.15s after
         * process start, and the first classification takes about 0.28s more
         * (2026-10-17): well inside this budget. A debug build with the old hazm
         * word lists took 8-10s to load (2026-10-13): the case this bound is for.
         */
        const val CLASSIFY_BUDGET_MS = 3_000L

        /**
         * How far back [classifyMissed] looks: well inside the pruning of ham
         * verdicts ([SpamRepository.SPAM_RETENTION_DAYS]), so a message without a
         * verdict there was never classified rather than pruned.
         */
        const val MISSED_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

        /** Younger messages may still be in [handle]; ingress is not done with them. */
        const val MISSED_GRACE_MS = 2L * 60L * 1000L
    }
}

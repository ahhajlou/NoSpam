package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.NotificationDecision
import com.nospam.nospam.core.model.PolicyInput
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SenderState
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface BackfillStatus {
    data object Idle : BackfillStatus
    data class Running(val processed: Int, val total: Int) : BackfillStatus
    data object Done : BackfillStatus
    data object Cancelled : BackfillStatus
    data object Failed : BackfillStatus
}

/**
 * One-shot background classification pass over the existing SMS history.
 * Runs on its own IO scope, never blocks the UI, and is fully idempotent:
 *
 * - Messages with a stored `message_verdict` on [start] are skipped, so an
 *   interrupted scan resumes without double-counting.
 * - No provider writes: no inserts, no READ flips, no notifications for old
 *   messages — content is read-only here.
 * - Fail-open: a crash / abort leaves messages unclassified (still visible in
 *   the inbox); the next scan re-processes them.
 * - All `sender_state` writes funnel through the shared [SpamStateWriter],
 *   which re-checks user overrides under the lock, so a concurrent ingress
 *   message or a mid-scan "not spam" correction is never clobbered.
 */
class SpamBackfillUseCase(
    private val telephony: TelephonyDataSource,
    private val classifier: SpamClassifier,
    private val db: NoSpamDatabase,
    private val context: android.content.Context? = null,
    private val spamStateWriter: SpamStateWriter,
    private val isSpamProtectionEnabled: suspend () -> Boolean = { true },
    private val externalScope: CoroutineScope? = null,
) {
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<BackfillStatus>(BackfillStatus.Idle)
    val status: StateFlow<BackfillStatus> = _status.asStateFlow()
    private val started = AtomicBoolean(false)
    @Volatile private var cancelled = false

    /** Idempotent start: no-op while a scan is already running or finished. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                run()
            } catch (e: Exception) {
                _status.value = BackfillStatus.Failed
            } finally {
                started.set(false)
                cancelled = false
            }
        }
    }

    /** Cancels between per-sender batches; ongoing writes finish atomically. */
    fun cancel() {
        cancelled = true
    }

    /** Test hook: force a fresh scan even if one already completed. */
    fun forceScanForTesting() {
        started.set(false)
        ensureStarted()
    }

    private suspend fun run() {
        val allMessages = telephony.getAllMessages().filter { it.type == MessageType.INBOX }
        val total = allMessages.size
        if (total == 0) {
            _status.value = BackfillStatus.Done
            return
        }

        val classifiedIds = try {
            db.messageVerdictDao.getAllMessageIds()
        } catch (e: Exception) {
            emptySet()
        }
        val outboundNormalized = telephony.getOutboundSenderAddresses()
            .map { normalize(it) }
            .toSet()
        val existingStates = try {
            db.senderStateDao.getAll().associateBy { it.normalizedAddress }
        } catch (e: Exception) {
            emptyMap()
        }
        val blocklistNormalized = try {
            db.blocklistDao.observeAll().first().map { normalize(it.address) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val contactIds = runCatching {
            telephony.getContacts(limit = BACKFILL_CONTACT_LIMIT).map { normalize(it.normalizedPhone) }.toSet()
        }.getOrDefault(emptySet())

        val scanEnabled = runCatching { isSpamProtectionEnabled() }.getOrDefault(true)
        val retentionCutoff = System.currentTimeMillis() - SpamRepository.SPAM_RETENTION_DAYS * 24L * 60L * 60L * 1000L

        val bySender = LinkedHashMap<String, MutableList<com.nospam.nospam.core.model.Message>>()
        for (m in allMessages) {
            bySender.getOrPut(normalize(m.address)) { mutableListOf() }.add(m)
        }

        var processed = 0
        val pendingStates = mutableListOf<PendingStateWrite>()
        val verdicts = mutableListOf<MessageVerdictEntity>()
        val now = System.currentTimeMillis()

        for ((key, messages) in bySender) {
            if (cancelled) {
                _status.value = BackfillStatus.Cancelled
                return
            }
            // User overrides are never touched — skip the whole sender, votes too.
            if (existingStates[key]?.isUserOverride == true) {
                processed += messages.size
                continue
            }

            val isBlocked = key in blocklistNormalized
            val isContact = key in contactIds
            val hasOutbound = key in outboundNormalized
            val allClassified = messages.all { it.id.value in classifiedIds }
            if (allClassified) {
                processed += messages.size
                continue
            }

            // Running per-sender state, seeded from today's DB so counts stay consistent.
            val seedEntity = existingStates[key]
            var running: SenderState? = seedEntity?.let {
                SenderState(it.normalizedAddress, it.state, it.spamCount, it.hamCount, it.isUserOverride, it.updatedAt)
            }

            for (m in messages) {
                if (cancelled) {
                    _status.value = BackfillStatus.Cancelled
                    return
                }
                if (m.id.value in classifiedIds) {
                    processed++
                    continue
                }
                val verdict = runCatching {
                    withTimeout(CLASSIFY_TIMEOUT_MS) {
                        classifier.classify(RawMessage(m.address, m.body, m.date, m.subscriptionId))
                    }
                }.getOrNull()
                if (verdict == null) {
                    processed++
                    statusProgress(processed, total)
                    continue
                }

                var policyOut = ThreadSpamPolicy.decideWithAddress(
                    key,
                    PolicyInput(
                        prevState = running,
                        isSpam = verdict.isSpam,
                        isContact = isContact,
                        hasOutbound = hasOutbound,
                        isBlocked = isBlocked,
                    ),
                )
                if (!scanEnabled) {
                    policyOut = policyOut.copy(
                        newState = policyOut.newState.copy(state = ThreadSpamState.CLEAN),
                        notification = NotificationDecision.NORMAL,
                    )
                }
                running = policyOut.newState

                // Retention: old messages get a sender-state vote but no verdict row.
                if (m.date >= retentionCutoff) {
                    verdicts.add(
                        MessageVerdictEntity(
                            messageId = m.id.value,
                            threadId = m.threadId.value,
                            normalizedAddress = key,
                            isSpam = verdict.isSpam,
                            score = verdict.score,
                            createdAt = now,
                        )
                    )
                }
                processed++
                statusProgress(processed, total)
            }

            running?.let {
            pendingStates.add(
                PendingStateWrite(
                    address = key,
                    computed = it.toEntity(now),
                    seedSpamCount = seedEntity?.spamCount ?: 0,
                    seedHamCount = seedEntity?.hamCount ?: 0,
                )
            )
        }
            if (pendingStates.size >= FLUSH_BATCH_SIZE) flush(pendingStates, verdicts)
        }

        flush(pendingStates, verdicts)
        _status.value = BackfillStatus.Done
    }

    private fun statusProgress(processed: Int, total: Int) {
        if (processed % PROGRESS_EVERY == 0) {
            _status.value = BackfillStatus.Running(processed, total)
        }
    }

    private suspend fun flush(
        pendingStates: MutableList<PendingStateWrite>,
        verdicts: MutableList<MessageVerdictEntity>,
    ) {
        spamStateWriter.upsertAllIfNotOverridden(pendingStates.toList())
        db.messageVerdictDao.insertAll(verdicts.toList())
        pendingStates.clear()
        verdicts.clear()
    }

    private fun SenderState.toEntity(now: Long): SenderStateEntity =
        SenderStateEntity(
            normalizedAddress = normalizedAddress,
            state = state,
            spamCount = spamCount,
            hamCount = hamCount,
            isUserOverride = isUserOverride,
            updatedAt = now,
        )

    private fun normalize(address: String): String =
        context?.let { com.nospam.nospam.core.telephony.PhoneNumberNormalizer.normalize(it, address) }
            ?: address.trim().uppercase()

    companion object {
        private const val CLASSIFY_TIMEOUT_MS = 8_000L
        private const val PROGRESS_EVERY = 100
        private const val FLUSH_BATCH_SIZE = 100
        private const val BACKFILL_CONTACT_LIMIT = 10_000
    }
}
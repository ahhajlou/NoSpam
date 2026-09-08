package com.nospam.nospam.core.model

enum class ThreadSpamState { CLEAN, MIXED, SPAM, TRUSTED, BLOCKED }
enum class NotificationDecision { NORMAL, SILENT, NONE }

data class MessageVerdict(
    val messageId: Long,
    val threadId: Long,
    val normalizedAddress: String,
    val isSpam: Boolean,
    val score: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val userLabel: Boolean? = null, // null = auto, true = user says spam, false = user says ham
)

data class SenderState(
    val normalizedAddress: String,
    val state: ThreadSpamState,
    val spamCount: Int = 0,
    val hamCount: Int = 0,
    val isUserOverride: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class PolicyInput(
    val prevState: SenderState?,
    val isSpam: Boolean,
    val isContact: Boolean = false,
    val hasOutbound: Boolean = false,
    val isBlocked: Boolean = false,
)

data class PolicyOutput(
    val newState: SenderState,
    val notification: NotificationDecision,
)

object ThreadSpamPolicy {
    fun decide(input: PolicyInput): PolicyOutput {
        val prev = input.prevState

        // BLOCKED wins over everything
        if (input.isBlocked) {
            val s = (prev?.copy(state = ThreadSpamState.BLOCKED, isUserOverride = prev.isUserOverride, updatedAt = System.currentTimeMillis()))
                ?: SenderState(prev?.normalizedAddress ?: "", ThreadSpamState.BLOCKED)
            // Ensure normalizedAddress preserved
            val normalized = prev?.normalizedAddress ?: ""
            return PolicyOutput(s.copy(normalizedAddress = normalized, state = ThreadSpamState.BLOCKED), NotificationDecision.NONE)
        }

        // TRUSTED override — never leaves
        if (prev?.state == ThreadSpamState.TRUSTED && prev.isUserOverride) {
            return PolicyOutput(prev, NotificationDecision.NORMAL)
        }
        // SPAM sticky — never leaves automatically (only user action changes it, which would have flipped to TRUSTED)
        if (prev?.state == ThreadSpamState.SPAM) {
            // If it's a user override SPAM, also sticky; if auto SPAM, sticky per spec.
            return PolicyOutput(prev, NotificationDecision.NONE)
        }
        if (prev?.state == ThreadSpamState.BLOCKED) {
            return PolicyOutput(prev, NotificationDecision.NONE)
        }

        // New sender
        if (prev == null) {
            return if (!input.isSpam) {
                val s = SenderState("", ThreadSpamState.CLEAN, spamCount = 0, hamCount = 1)
                PolicyOutput(s, NotificationDecision.NORMAL)
            } else {
                // spam from contact -> MIXED not SPAM
                if (input.isContact) {
                    val s = SenderState("", ThreadSpamState.MIXED, spamCount = 1, hamCount = 0)
                    PolicyOutput(s, NotificationDecision.SILENT)
                } else {
                    val s = SenderState("", ThreadSpamState.SPAM, spamCount = 1, hamCount = 0)
                    PolicyOutput(s, NotificationDecision.NONE)
                }
            }
        }

        // Existing CLEAN / MIXED — handle ham and spam
        // Contacts / replied senders can never be auto-promoted to SPAM
        val protectFromSpam = input.isContact || input.hasOutbound

        if (!input.isSpam) {
            // Ham: if MIXED, stay MIXED (spec: mixed never flaps back to clean on single ham)
            // If CLEAN, stay CLEAN. TRUSTED/BLOCKED/SPAM already returned.
            return when (prev.state) {
                ThreadSpamState.CLEAN -> {
                    val ns = prev.copy(hamCount = prev.hamCount + 1, updatedAt = System.currentTimeMillis())
                    PolicyOutput(ns, NotificationDecision.NORMAL)
                }
                ThreadSpamState.MIXED -> {
                    val ns = prev.copy(hamCount = prev.hamCount + 1, updatedAt = System.currentTimeMillis())
                    PolicyOutput(ns, NotificationDecision.NORMAL)
                }
                else -> PolicyOutput(prev, NotificationDecision.NORMAL)
            }
        } else {
            // Spam
            return when (prev.state) {
                ThreadSpamState.CLEAN -> {
                    if (protectFromSpam) {
                        val ns = prev.copy(state = ThreadSpamState.MIXED, spamCount = prev.spamCount + 1, updatedAt = System.currentTimeMillis())
                        PolicyOutput(ns, NotificationDecision.SILENT)
                    } else {
                        val ns = prev.copy(state = ThreadSpamState.MIXED, spamCount = prev.spamCount + 1, updatedAt = System.currentTimeMillis())
                        PolicyOutput(ns, NotificationDecision.SILENT)
                    }
                }
                ThreadSpamState.MIXED -> {
                    val newSpam = prev.spamCount + 1
                    val total = newSpam + prev.hamCount
                    val ratio = if (total == 0) 0.0 else newSpam.toDouble() / total
                    val shouldGraduate = !protectFromSpam && newSpam >= 3 && total >= 3 && ratio >= 0.8
                    if (shouldGraduate) {
                        val ns = prev.copy(state = ThreadSpamState.SPAM, spamCount = newSpam, updatedAt = System.currentTimeMillis())
                        PolicyOutput(ns, NotificationDecision.NONE)
                    } else {
                        val ns = prev.copy(state = ThreadSpamState.MIXED, spamCount = newSpam, updatedAt = System.currentTimeMillis())
                        PolicyOutput(ns, NotificationDecision.SILENT)
                    }
                }
                else -> PolicyOutput(prev, NotificationDecision.SILENT)
            }
        }
    }

    // Helper for callers that need to preserve normalizedAddress from prev or input
    fun decideWithAddress(normalizedAddress: String, input: PolicyInput): PolicyOutput {
        val out = decide(input)
        return out.copy(newState = out.newState.copy(normalizedAddress = normalizedAddress))
    }
}

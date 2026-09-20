// SPDX-License-Identifier: GPL-3.0-or-later

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
    /**
     * Spam messages a MIXED sender must accumulate before it can graduate to
     * SPAM. Below this, one bad message from an otherwise fine sender is never
     * enough to hide the conversation.
     */
    const val GRADUATION_MIN_SPAM = 3

    /**
     * Share of a MIXED sender's messages that must be spam before it graduates.
     * Guards the sender that mixes both — a bank sending OTPs and promos from
     * one short code should stay in the inbox.
     */
    const val GRADUATION_MIN_SPAM_RATIO = 0.8

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

        // TRUSTED never leaves. The check is on the state alone, not on
        // isUserOverride: a TRUSTED sender whose override flag was false used to
        // fall through every guard into the `else` arm of the ham and spam
        // branches, returning unchanged but by accident rather than by rule.
        // Nothing constructs that combination today, and if a system-derived
        // allowlist is ever added (contacts, or a long clean history) it should
        // be honoured here exactly like a user's own decision.
        if (prev?.state == ThreadSpamState.TRUSTED) {
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

        // Contacts, and any sender the user has already replied to, are never
        // auto-promoted to SPAM — at most MIXED. This is the strongest
        // anti-false-positive signal available, so it is checked before the
        // classifier's verdict is allowed to move a conversation to Spam.
        val protectFromSpam = input.isContact || input.hasOutbound

        // New sender
        if (prev == null) {
            return if (!input.isSpam) {
                val s = SenderState("", ThreadSpamState.CLEAN, spamCount = 0, hamCount = 1)
                PolicyOutput(s, NotificationDecision.NORMAL)
            } else {
                // Spam from a contact or a sender we have written to -> MIXED, not SPAM.
                // `hasOutbound` matters here even with no prior SenderState row: texting a
                // business first and getting a promotional reply is exactly this case.
                if (protectFromSpam) {
                    val s = SenderState("", ThreadSpamState.MIXED, spamCount = 1, hamCount = 0)
                    PolicyOutput(s, NotificationDecision.SILENT)
                } else {
                    val s = SenderState("", ThreadSpamState.SPAM, spamCount = 1, hamCount = 0)
                    PolicyOutput(s, NotificationDecision.NONE)
                }
            }
        }

        // Existing CLEAN / MIXED — handle ham and spam
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
                    // A sender with ham history goes to MIXED on its first spam
                    // whether protected or not; the protection only decides
                    // whether MIXED can later graduate to SPAM, below.
                    val ns = prev.copy(state = ThreadSpamState.MIXED, spamCount = prev.spamCount + 1, updatedAt = System.currentTimeMillis())
                    PolicyOutput(ns, NotificationDecision.SILENT)
                }
                ThreadSpamState.MIXED -> {
                    val newSpam = prev.spamCount + 1
                    val total = newSpam + prev.hamCount
                    val ratio = if (total == 0) 0.0 else newSpam.toDouble() / total
                    // `total >= GRADUATION_MIN_SPAM` needs no separate check: total is
                    // newSpam + hamCount, so it is always at least newSpam.
                    val shouldGraduate = !protectFromSpam &&
                        newSpam >= GRADUATION_MIN_SPAM &&
                        ratio >= GRADUATION_MIN_SPAM_RATIO
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

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

/**
 * Where a sender's conversations go. CLEAN, MIXED and SUSPECTED stay in the
 * inbox; SPAM and BLOCKED are in Spam & blocked. TRUSTED is the user's
 * "Not spam". See [ThreadSpamPolicy.deriveState] for how the automatic states
 * follow from a sender's counts.
 */
enum class ThreadSpamState { CLEAN, MIXED, SPAM, TRUSTED, BLOCKED, SUSPECTED }
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
    /**
     * The user has written to this sender at some point. Kept on the sender
     * rather than read from the conversation, so deleting the conversation does
     * not take the protection away.
     */
    val hasReplied: Boolean = false,
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

/**
 * The spam routing model agreed on 2026-09-15 (TODO.md, "Spam routing — agreed
 * model"). Evaluated top to bottom, first match wins:
 *
 * 1. Blocked by the user: Spam, no notification.
 * 2. Marked "Not spam" (TRUSTED) or "Report spam" (a SPAM override) by the user:
 *    left as the user set it.
 * 3. A saved contact: not classified at all. The caller does not run the
 *    classifier, and nothing about the sender changes.
 * 4. Everything else follows from the sender's counts, through [deriveState].
 *
 * The automatic state is a function of the counts alone, so the outcome does not
 * depend on the order messages arrived in, and one ham message from a sender in
 * Spam brings it back to the inbox.
 */
object ThreadSpamPolicy {
    /**
     * The automatic state for a sender with these counts:
     * - no spam: CLEAN;
     * - spam, but the sender has also sent ham or the user has replied: MIXED,
     *   in the inbox with the spam labelled. Nothing that has ever produced a
     *   legitimate message is hidden;
     * - exactly one spam message and nothing else: SUSPECTED (probation), in the
     *   inbox, labelled;
     * - two or more spam messages and nothing else: SPAM.
     */
    fun deriveState(spamCount: Int, hamCount: Int, hasReplied: Boolean): ThreadSpamState = when {
        spamCount <= 0 -> ThreadSpamState.CLEAN
        hamCount > 0 || hasReplied -> ThreadSpamState.MIXED
        spamCount == 1 -> ThreadSpamState.SUSPECTED
        else -> ThreadSpamState.SPAM
    }

    /** [deriveState] for an existing sender's own counts. */
    fun derivedState(state: SenderState): ThreadSpamState =
        deriveState(state.spamCount, state.hamCount, state.hasReplied)

    fun decide(input: PolicyInput): PolicyOutput {
        val prev = input.prevState

        if (input.isBlocked) {
            val s = prev?.copy(state = ThreadSpamState.BLOCKED, updatedAt = System.currentTimeMillis())
                ?: SenderState("", ThreadSpamState.BLOCKED)
            return PolicyOutput(s, NotificationDecision.NONE)
        }

        // The user's own decisions are never changed by a message. TRUSTED is
        // honoured on the state alone, whatever the override flag says.
        if (prev?.state == ThreadSpamState.TRUSTED) return PolicyOutput(prev, NotificationDecision.NORMAL)
        if (prev?.isUserOverride == true) {
            return PolicyOutput(prev, if (prev.state == ThreadSpamState.SPAM) NotificationDecision.NONE else NotificationDecision.NORMAL)
        }
        if (prev?.state == ThreadSpamState.BLOCKED) return PolicyOutput(prev, NotificationDecision.NONE)

        // Saved contacts bypass the classifier: no count, no label, no routing.
        if (input.isContact) {
            return PolicyOutput(prev ?: SenderState("", ThreadSpamState.CLEAN), NotificationDecision.NORMAL)
        }

        val spamCount = (prev?.spamCount ?: 0) + if (input.isSpam) 1 else 0
        val hamCount = (prev?.hamCount ?: 0) + if (input.isSpam) 0 else 1
        val hasReplied = (prev?.hasReplied ?: false) || input.hasOutbound
        val state = deriveState(spamCount, hamCount, hasReplied)
        val newState = (prev ?: SenderState("", state)).copy(
            state = state,
            spamCount = spamCount,
            hamCount = hamCount,
            hasReplied = hasReplied,
            updatedAt = System.currentTimeMillis(),
        )
        val notification = when {
            !input.isSpam -> NotificationDecision.NORMAL
            state == ThreadSpamState.SPAM -> NotificationDecision.NONE
            // Spam that stays in the inbox: labelled and silent. Whether it posts
            // a quiet notification is the user's setting, applied by the caller.
            else -> NotificationDecision.SILENT
        }
        return PolicyOutput(newState, notification)
    }

    // Helper for callers that need to preserve normalizedAddress from prev or input
    fun decideWithAddress(normalizedAddress: String, input: PolicyInput): PolicyOutput {
        val out = decide(input)
        return out.copy(newState = out.newState.copy(normalizedAddress = normalizedAddress))
    }
}

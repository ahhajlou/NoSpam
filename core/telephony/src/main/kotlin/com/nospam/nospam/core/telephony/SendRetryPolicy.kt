// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.telephony.SmsManager

/**
 * When a failed send is tried again, and when it stops. Pure, so it is unit-tested
 * on its own; [SendRetry] applies it.
 *
 * Modelled on AOSP Messaging (`ProcessPendingMessagesAction`, `SmsSender`,
 * `BugleGservicesKeys`): only a failure that can clear by itself is retried, the
 * delay starts at 5 s and doubles up to 2 hours, and retrying stops 20 minutes
 * after the first failure, when the message is marked failed for a manual retry.
 * One difference: AOSP gives up at once on "radio off", while Google Messages
 * waits for the radio to come back (seen on the emulator, 2026-09-20). Airplane
 * mode ends, so radio off is retried too.
 */
internal object SendRetryPolicy {
    const val INITIAL_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 2 * 60 * 60 * 1000L
    const val WINDOW_MS = 20 * 60 * 1000L

    /** A send with no result after this long is treated as failed, not left "Sending…". */
    const val STUCK_AFTER_MS = 5 * 60 * 1000L

    /** Failures the network or the user clears without changing the message. */
    fun isTemporary(resultCode: Int): Boolean =
        resultCode == SmsManager.RESULT_ERROR_NO_SERVICE || resultCode == SmsManager.RESULT_ERROR_RADIO_OFF

    /** Delay before retry number [attempt] + 1 (attempt 0 is the original send). */
    fun delayFor(attempt: Int): Long {
        var delay = INITIAL_DELAY_MS
        repeat(attempt.coerceAtMost(30)) { delay = (delay * 2).coerceAtMost(MAX_DELAY_MS) }
        return delay
    }

    /**
     * One message's retry state.
     * @param firstFailureAt when the current window started; 0 until a temporary failure.
     * @param attempt how many retries have been sent in this window.
     * @param lastSentAt when it was last handed to the radio.
     * @param nextAt when it is due again; 0 while it is not waiting.
     */
    data class Entry(
        val subscriptionId: Int?,
        val deliveryReport: Boolean,
        val firstFailureAt: Long = 0L,
        val attempt: Int = 0,
        val lastSentAt: Long = 0L,
        val nextAt: Long = 0L,
    ) {
        fun encode(): String = listOf(subscriptionId ?: NO_SUB, if (deliveryReport) 1 else 0, firstFailureAt, attempt, lastSentAt, nextAt).joinToString(",")

        companion object {
            private const val NO_SUB = Int.MIN_VALUE

            fun decode(value: String): Entry? = runCatching {
                val p = value.split(",")
                Entry(
                    subscriptionId = p[0].toInt().takeIf { it != NO_SUB },
                    deliveryReport = p[1] == "1",
                    firstFailureAt = p[2].toLong(),
                    attempt = p[3].toInt(),
                    lastSentAt = p[4].toLong(),
                    nextAt = p[5].toLong(),
                )
            }.getOrNull()
        }
    }

    sealed interface Decision {
        /** Wait, then send again at [entry].nextAt. */
        data class Retry(val entry: Entry) : Decision

        /** Already waiting (another part of the same message failed too). */
        data object AlreadyWaiting : Decision

        /** The window is over: mark it failed for a manual retry. */
        data object GiveUp : Decision
    }

    /** What a temporary failure at [now] means for a message in state [entry]. */
    fun onTemporaryFailure(entry: Entry, now: Long): Decision {
        if (entry.nextAt > now) return Decision.AlreadyWaiting
        val start = if (entry.firstFailureAt == 0L) now else entry.firstFailureAt
        if (now - start >= WINDOW_MS) return Decision.GiveUp
        return Decision.Retry(entry.copy(firstFailureAt = start, nextAt = now + delayFor(entry.attempt)))
    }

    /** A message handed to the radio at [lastSentAt] with no result by [now]. */
    fun isStuck(lastSentAt: Long, now: Long): Boolean = lastSentAt > 0 && now - lastSentAt >= STUCK_AFTER_MS
}

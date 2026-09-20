// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

/** Unicode isolates: everything between them keeps its own direction. */
private const val LEFT_TO_RIGHT_ISOLATE = '⁦'
private const val POP_DIRECTIONAL_ISOLATE = '⁩'

/** Digits plus the punctuation a written phone number may contain. */
private const val PHONE_PUNCTUATION = "+()-. "

/**
 * Wraps left-to-right text so it keeps its own direction inside a
 * right-to-left layout.
 *
 * Phone numbers are the reason. In a Persian layout the bidi algorithm reads a
 * bare "+98912..." as part of the surrounding right-to-left run, which moves
 * the leading "+" to the other end; the same happens to "12/2" in the message
 * counter, which can read as "2/12". The isolate characters are invisible and
 * pin the direction of what they wrap.
 *
 * Written out rather than using `BidiFormatter.getInstance()`, which decides
 * what to add from `Locale.getDefault()` — that adds nothing at all when the
 * default locale is left-to-right, even though the app's own layout may be
 * right-to-left through a per-app language.
 */
fun isolateLtr(value: String): String =
    if (value.isEmpty()) value else "$LEFT_TO_RIGHT_ISOLATE$value$POP_DIRECTIONAL_ISOLATE"

/**
 * Isolates [value] only when it reads as a phone number.
 *
 * Sender addresses are either numbers or alphanumeric ids ("IRANCELL"), and
 * only the first kind is reordered by the bidi algorithm: a run of letters
 * already holds together. Isolating everything would also alter text that is
 * matched elsewhere — UI tests and the end-to-end flows look sender ids up by
 * their exact text, and invisible characters are still characters.
 */
fun isolateIfPhoneNumber(value: String): String =
    if (looksLikePhoneNumber(value)) isolateLtr(value) else value

internal fun looksLikePhoneNumber(value: String): Boolean =
    value.any { it.isDigit() } && value.all { it.isDigit() || it in PHONE_PUNCTUATION }

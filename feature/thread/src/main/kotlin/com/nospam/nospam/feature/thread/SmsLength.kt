// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

/**
 * How a draft will be split into SMS parts.
 *
 * @param segments how many SMS the text will be sent as (0 for empty text)
 * @param remainingInSegment characters left before another part starts
 * @param unicode true when the text needs UCS-2, which shortens every part
 */
data class SmsLength(
    val segments: Int,
    val remainingInSegment: Int,
    val unicode: Boolean,
)

// GSM 03.38: a single part holds 160 septets, but a concatenated one spends 7
// of them on the part header, leaving 153. UCS-2 is 70 and 67 for the same reason.
private const val GSM_SINGLE = 160
private const val GSM_CONCATENATED = 153
private const val UNICODE_SINGLE = 70
private const val UNICODE_CONCATENATED = 67

/** Characters the GSM default alphabet encodes in one septet. */
private const val GSM_BASIC =
    "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

/** Encodable, but as an escape plus the character, so they cost two septets. */
private const val GSM_EXTENDED = "^{}\\[~]|€"

/**
 * Counts a draft the way the network will.
 *
 * Any character outside the GSM alphabet — a Persian letter, an emoji, a curly
 * quote — switches the whole message to UCS-2, where a part is 70 characters
 * instead of 160. That is why the counter has to be shown: the limit more than
 * halves on a single keystroke, and the user is charged per part.
 */
fun smsLength(text: String): SmsLength {
    if (text.isEmpty()) return SmsLength(segments = 0, remainingInSegment = GSM_SINGLE, unicode = false)

    val unicode = text.any { it !in GSM_BASIC && it !in GSM_EXTENDED }
    // Emoji and other supplementary characters take two UTF-16 units, and the
    // network counts both, so length (not code points) is the right unit here.
    val units = if (unicode) text.length else text.sumOf { if (it in GSM_EXTENDED) 2 else 1 }.toInt()

    val single = if (unicode) UNICODE_SINGLE else GSM_SINGLE
    val concatenated = if (unicode) UNICODE_CONCATENATED else GSM_CONCATENATED

    if (units <= single) {
        return SmsLength(segments = 1, remainingInSegment = single - units, unicode = unicode)
    }
    val segments = (units + concatenated - 1) / concatenated
    val remaining = segments * concatenated - units
    return SmsLength(segments = segments, remainingInSegment = remaining, unicode = unicode)
}

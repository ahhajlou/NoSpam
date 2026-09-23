// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import android.content.Intent
import com.nospam.nospam.core.model.TelephonyConstants

/** Where an incoming intent asks the app to go. */
sealed interface LaunchTarget {
    /** An existing conversation, e.g. from tapping its notification. */
    data class Thread(val threadId: Long, val address: String?) : LaunchTarget

    /** A conversation with [address], starting with [body] in the compose box. */
    data class Compose(val address: String, val body: String?) : LaunchTarget
}

/** Longest address accepted from another app; anything longer is not a recipient. */
internal const val MAX_ADDRESS_LENGTH = 64

private val SCHEMES = TelephonyConstants.SEND_SCHEMES.toSet()

/**
 * Reads a launch intent's parts into a [LaunchTarget], or null when the intent
 * is not a request to open a conversation. Every value comes from another app
 * (CLAUDE.md §12), so anything malformed is ignored rather than trusted.
 *
 * - [threadId] greater than 0 on `VIEW` or `SENDTO` opens that thread; our own
 *   notification sends `VIEW` with the sender's `sms:` URI and this extra.
 * - Otherwise `SENDTO` or `VIEW` with an `sms:`, `smsto:`, `mms:` or `mmsto:`
 *   URI opens a conversation with the first recipient it names. The body comes
 *   from the `sms_body` extra, else [text] (`EXTRA_TEXT`), else a `body=`
 *   parameter in the URI.
 *
 * @param schemeSpecificPart the URI after its scheme, already decoded, e.g.
 * `+15551234,+15555678?body=hi`.
 */
fun parseLaunchIntent(
    action: String?,
    scheme: String?,
    schemeSpecificPart: String?,
    threadId: Long,
    smsBody: String?,
    text: String?,
): LaunchTarget? {
    if (action != Intent.ACTION_SENDTO && action != Intent.ACTION_VIEW) return null
    val validScheme = scheme?.lowercase() in SCHEMES
    val recipients = if (validScheme) schemeSpecificPart?.substringBefore('?') else null
    val address = recipients
        ?.split(',', ';')
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?.takeIf { it.length <= MAX_ADDRESS_LENGTH }
    if (threadId > 0) return LaunchTarget.Thread(threadId, address)
    address ?: return null
    val body = smsBody ?: text ?: schemeSpecificPart?.let(::bodyParameter)
    return LaunchTarget.Compose(address, body?.takeIf { it.isNotEmpty() })
}

private fun bodyParameter(schemeSpecificPart: String): String? =
    schemeSpecificPart.substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .firstOrNull { it.startsWith("body=") }
        ?.removePrefix("body=")

/** [parseLaunchIntent] over a real [Intent]; never throws on a malformed one. */
fun Intent.toLaunchTarget(): LaunchTarget? = runCatching {
    parseLaunchIntent(
        action = action,
        scheme = data?.scheme,
        schemeSpecificPart = data?.schemeSpecificPart,
        threadId = getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L),
        smsBody = getStringExtra(EXTRA_SMS_BODY),
        text = getStringExtra(Intent.EXTRA_TEXT),
    )
}.getOrNull()

/** Extra most SMS-sending apps use for the message text. */
private const val EXTRA_SMS_BODY = "sms_body"

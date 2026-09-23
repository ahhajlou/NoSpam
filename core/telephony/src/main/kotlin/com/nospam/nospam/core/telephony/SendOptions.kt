// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

/** How one message is sent. */
data class SendOptions(
    /** Ask the network to report when the message reaches the recipient. */
    val deliveryReport: Boolean = false,
)

/** The [SendOptions] for a SIM, answered without blocking. */
fun interface SendOptionsProvider {
    fun forSubscription(subscriptionId: Int?): SendOptions
}

/**
 * Where sends that cannot take a parameter get their options: the direct-reply
 * service ([com.nospam.nospam.core.telephony.service.HeadlessSmsSendService]) is
 * started by the system with only an intent. `:app` installs a provider backed
 * by the user's settings in `Application.onCreate`, which always runs before a
 * service starts; until then, and in tests, it is plain defaults. This keeps
 * core:telephony from depending on the preferences module.
 */
object SendOptionsRegistry {
    @Volatile
    var provider: SendOptionsProvider = SendOptionsProvider { SendOptions() }
}

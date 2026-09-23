// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import com.nospam.nospam.core.model.DeliveryStatus

/**
 * The provider's `sms.status` values (the constants in `Telephony.TextBasedSmsColumns`,
 * repeated so these rules stay plain Kotlin and unit-testable).
 */
internal object ProviderStatus {
    const val NONE = -1
    const val COMPLETE = 0
    const val PENDING = 32
    const val FAILED = 64
}

/**
 * The provider status for a status report's TP-Status (3GPP TS 23.040
 * §9.2.3.15): below 0x20 the message was delivered, 0x20–0x3F the network is
 * still trying, anything higher is a permanent or given-up failure.
 */
internal fun statusForReport(tpStatus: Int): Int = when {
    tpStatus < 0x20 -> ProviderStatus.COMPLETE
    tpStatus < 0x40 -> ProviderStatus.PENDING
    else -> ProviderStatus.FAILED
}

/**
 * The status to store after a report, or null to leave the row alone. A long
 * message is sent in parts and each part reports on its own, all against one
 * row: a failed part stays failed whatever arrives after it, and "delivered" or
 * "still trying" never replaces a failure. A late "still trying" also does not
 * undo "delivered".
 */
internal fun nextDeliveryStatus(current: Int, reported: Int): Int? = when {
    current == ProviderStatus.FAILED -> null
    reported == ProviderStatus.FAILED -> ProviderStatus.FAILED
    reported == ProviderStatus.COMPLETE -> ProviderStatus.COMPLETE.takeIf { current != it }
    current == ProviderStatus.COMPLETE -> null
    else -> ProviderStatus.PENDING.takeIf { current != it }
}

/** How a stored `sms.status` reads in the app. */
internal fun deliveryStatusOf(providerStatus: Int): DeliveryStatus = when {
    providerStatus < 0 -> DeliveryStatus.NONE
    providerStatus < ProviderStatus.PENDING -> DeliveryStatus.DELIVERED
    providerStatus < ProviderStatus.FAILED -> DeliveryStatus.PENDING
    else -> DeliveryStatus.FAILED
}

package com.example.nospam.core.telephony

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Resolves the [SmsManager] for [subscriptionId], falling back to the default
 * subscription.
 *
 * Uses the modern APIs — [Context.getSystemService] and
 * [SmsManager.createForSubscriptionId] (API 31+) — instead of the deprecated
 * `getSmsManagerForSubscriptionId` / `getDefault` static accessors, which are
 * kept only as a fallback below API 31 (minSdk 26).
 */
internal fun Context.resolveSmsManager(subscriptionId: Int?): SmsManager {
    val resolvedId = subscriptionId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        ?: SubscriptionManager.getDefaultSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val base = getSystemService(SmsManager::class.java) ?: return legacySmsManager(resolvedId)
        return if (resolvedId != null) base.createForSubscriptionId(resolvedId) else base
    }
    return legacySmsManager(resolvedId)
}

@Suppress("DEPRECATION")
private fun legacySmsManager(subscriptionId: Int?): SmsManager =
    if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    else SmsManager.getDefault()

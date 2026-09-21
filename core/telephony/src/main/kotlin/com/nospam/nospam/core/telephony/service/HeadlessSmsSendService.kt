// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.RemoteInput
import com.nospam.nospam.core.model.TelephonyConstants
import com.nospam.nospam.core.telephony.TelephonyMapper
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.telephony.SmsSender

/**
 * Handles notification direct-reply (RESPOND_VIA_MESSAGE) with the correct
 * subscription and writes the sent message to the provider so it appears
 * in the thread. Keeps only platform APIs — no core:* dependencies except
 * core:model constants and same-module helpers.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (intent.action != TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return START_NOT_STICKY
        }
        val (address, text, rawSubscriptionId) = extractReply(intent)
        if (address.isNullOrBlank() || text.isNullOrBlank()) {
            Log.w(TAG, "Ignoring reply with missing address/text")
            return START_NOT_STICKY
        }
        val subscriptionId = validateSubscriptionId(rawSubscriptionId)
        if (rawSubscriptionId != null && subscriptionId == null) {
            Log.w(TAG, "Ignoring reply with invalid subscription_id: $rawSubscriptionId")
            return START_NOT_STICKY
        }
        // The default SMS app persists its own messages: as OUTBOX first, then
        // SENT or FAILED once the radio reports. No row (not the default app)
        // must not stop the send; the system stores that message itself.
        val row = runCatching {
            contentResolver.insert(
                Telephony.Sms.Outbox.CONTENT_URI,
                TelephonyMapper.buildOutboxValues(address, text, System.currentTimeMillis(), subscriptionId),
            )
        }.getOrNull()
        try {
            SmsSender.send(this, address, text, subscriptionId, row)
            cancelNotificationFor(address)
        } catch (e: Exception) {
            Log.e(TAG, "Direct reply failed", e)
            if (row != null) SmsSender.setType(this, row, MessageType.FAILED)
        }
        return START_NOT_STICKY
    }

    private fun cancelNotificationFor(address: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        // Thread id is the notification id used by AppSmsReceiver.
        runCatching {
            val threadId = Telephony.Threads.getOrCreateThreadId(this, address)
            manager.cancel(threadId.toInt())
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun validateSubscriptionId(subId: Int?): Int? {
        if (subId == null) return null
        return try {
            val sm = getSystemService(SubscriptionManager::class.java) ?: return null
            val active = sm.activeSubscriptionInfoList ?: return null
            if (active.any { it.subscriptionId == subId }) subId else null
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot validate subscription_id without permission", e)
            null
        }
    }

    companion object {
        private const val TAG = "HeadlessSmsSend"

        /**
         * Pure helpers, unit-testable without a device. Framework extraction
         * (Uri/RemoteInput) stays in [extractReply].
         */
        fun pickReplyText(remoteInputText: String?, extraText: String?, smsBody: String?): String? {
            val text = remoteInputText ?: extraText ?: smsBody
            return text?.takeIf { it.isNotBlank() }
        }

        fun normalizeSubscriptionId(hasExtra: Boolean, raw: Int): Int? =
            if (hasExtra && raw != SubscriptionManager.INVALID_SUBSCRIPTION_ID) raw else null

        fun extractReply(intent: Intent): Triple<String?, String?, Int?> {
            val address = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }
            val remote = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(TelephonyConstants.KEY_TEXT_REPLY)?.toString()
            val text = pickReplyText(
                remote,
                intent.getStringExtra("android.intent.extra.TEXT"),
                intent.getStringExtra("sms_body"),
            )
            val subId = normalizeSubscriptionId(
                intent.hasExtra("subscription_id"),
                intent.getIntExtra("subscription_id", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
            )
            return Triple(address, text, subId)
        }
    }
}

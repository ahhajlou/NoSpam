package com.example.nospam.core.telephony.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.RemoteInput
import com.example.nospam.core.model.TelephonyConstants
import com.example.nospam.core.telephony.TelephonyMapper
import com.example.nospam.core.telephony.resolveSmsManager

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
        val (address, text, subscriptionId) = extractReply(intent)
        if (address.isNullOrBlank() || text.isNullOrBlank()) {
            Log.w(TAG, "Ignoring reply with missing address/text")
            return START_NOT_STICKY
        }
        try {
            smsManagerFor(subscriptionId).sendTextMessage(address, null, text, null, null)
            // Default SMS app must persist its own sent messages.
            contentResolver.insert(
                Telephony.Sms.Sent.CONTENT_URI,
                TelephonyMapper.buildSentValues(address, text, System.currentTimeMillis(), subscriptionId),
            )
            cancelNotificationFor(address)
        } catch (e: Exception) {
            Log.e(TAG, "Direct reply failed", e)
        }
        return START_NOT_STICKY
    }

    private fun smsManagerFor(subscriptionId: Int?): SmsManager =
        resolveSmsManager(subscriptionId)

    private fun cancelNotificationFor(address: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        // Thread id is the notification id used by AppSmsReceiver.
        runCatching {
            val threadId = Telephony.Threads.getOrCreateThreadId(this, address)
            manager.cancel(threadId.toInt())
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

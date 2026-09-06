package com.nospam.nospam.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

@Deprecated(
    "Superseded by :app AppSmsReceiver, which classifies via SmsIngressUseCase. " +
        "Kept until end-of-project cleanup; no longer registered in the manifest."
)
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages[0].displayOriginatingAddress
        val body = messages.joinToString("") { it.messageBody ?: "" }
        Log.d("SmsReceiver", "Deliver from $sender: $body")
        // Classification and insertion delegated to RealTelephonyDataSource + SpamClassifier via WorkManager in Phase 5
        // For now, insert as unread ham
        val values = com.nospam.nospam.core.telephony.TelephonyMapper.buildMessageValues(sender ?: "Unknown", body, System.currentTimeMillis(), 0)
        try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "insert failed", e)
        }
    }
}

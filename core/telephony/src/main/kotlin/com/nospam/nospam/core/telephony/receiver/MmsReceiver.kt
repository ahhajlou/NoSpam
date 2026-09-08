package com.nospam.nospam.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MmsReceiver", "MMS received (stub) action=${intent.action}")
        // Placeholder until full MMS parsing lands (Phase 9.6)
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.Telephony.Sms.ADDRESS, "MMS")
                put(android.provider.Telephony.Sms.BODY, "Media message not supported yet")
                put(android.provider.Telephony.Sms.DATE, System.currentTimeMillis())
                put(android.provider.Telephony.Sms.TYPE, android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(android.provider.Telephony.Sms.READ, 1)
            }
            context.contentResolver.insert(android.provider.Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (_: Exception) {}
    }
}

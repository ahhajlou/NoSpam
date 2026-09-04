package com.example.nospam.core.telephony.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val uri = it.data
            val text = it.getStringExtra("android.intent.extra.TEXT") ?: it.getStringExtra("sms_body")
            if (uri != null && text != null) {
                val address = uri.schemeSpecificPart
                try {
                    SmsManager.getDefault().sendTextMessage(address, null, text, null, null)
                } catch (_: Exception) { }
            }
        }
        return START_NOT_STICKY
    }
}

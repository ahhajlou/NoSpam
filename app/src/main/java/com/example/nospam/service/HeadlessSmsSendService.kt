package com.example.nospam.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: Handle quick-reply messages (e.g., from notifications or Android Auto)
        return START_NOT_STICKY
    }
}
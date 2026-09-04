package com.example.nospam.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MmsReceiver", "MMS received (stub) action=${intent.action}")
        // Full MMS parsing deferred (Phase 5+), per TASKS.md
    }
}

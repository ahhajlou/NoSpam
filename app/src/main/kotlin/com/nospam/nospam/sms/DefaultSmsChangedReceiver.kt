// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class DefaultSmsChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
        Log.d("DefaultSmsChanged", "Default SMS package changed")
    }
}

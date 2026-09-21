// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.nospam.nospam.core.telephony.SmsSender

/**
 * Receives the radio's verdict for a message this app sent (the `sentIntent`
 * built in [SmsSender]) and moves the provider row from OUTBOX to SENT or FAILED.
 *
 * Not exported: the only sender is the system, through a PendingIntent naming
 * this class. Anything malformed is ignored, never a crash.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        val uri = intent.data ?: return
        val isOurRow = uri.authority == Telephony.Sms.CONTENT_URI.authority &&
            runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L) > 0
        if (!isOurRow) return
        SmsSender.recordResult(context, uri, resultCode)
    }

    companion object {
        const val ACTION_SMS_SENT = "com.nospam.nospam.core.telephony.SMS_SENT"
    }
}

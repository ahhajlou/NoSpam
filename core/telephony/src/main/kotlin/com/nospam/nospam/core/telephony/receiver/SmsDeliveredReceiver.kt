// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.nospam.nospam.core.telephony.SmsSender

/**
 * Receives a delivery report for a message sent with one requested (the
 * `deliveryIntent` built in [SmsSender]) and records it on the provider row.
 *
 * Not exported: the only sender is the system, through a PendingIntent naming
 * this class. Anything malformed is ignored, never a crash.
 */
class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_DELIVERED) return
        val uri = intent.data ?: return
        val isOurRow = uri.authority == Telephony.Sms.CONTENT_URI.authority &&
            runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L) > 0
        if (!isOurRow) return
        val pdu = runCatching { intent.getByteArrayExtra(EXTRA_PDU) }.getOrNull() ?: return
        val format = runCatching { intent.getStringExtra(EXTRA_FORMAT) }.getOrNull()
        SmsSender.recordDelivery(context, uri, pdu, format)
    }

    companion object {
        const val ACTION_SMS_DELIVERED = "com.nospam.nospam.core.telephony.SMS_DELIVERED"
        // Extras the platform adds to a delivery intent.
        private const val EXTRA_PDU = "pdu"
        private const val EXTRA_FORMAT = "format"
    }
}

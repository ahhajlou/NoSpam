// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.telephony.receiver.SmsSentReceiver

/**
 * Sends an SMS and keeps its provider row honest.
 *
 * `SmsManager.sendTextMessage` returns as soon as the request is queued; radio
 * off, no service or an invalid number only show up later, through the sent
 * intent. Without one, every message looks sent whether or not it went out.
 * So the row is written as OUTBOX first, and [SmsSentReceiver] moves it to SENT
 * or FAILED when the radio reports back.
 */
internal object SmsSender {
    private const val TAG = "SmsSender"

    /** The provider's own default for a row with no error. */
    private const val NO_ERROR = -1

    /**
     * Hands [body] to the radio. [messageUri] is the OUTBOX row to update with
     * the outcome, or null when this app could not write one (not the default
     * SMS app; the system then stores the message itself).
     */
    fun send(context: Context, address: String, body: String, subscriptionId: Int?, messageUri: Uri?) {
        val mgr = context.resolveSmsManager(subscriptionId)
        val parts = mgr.divideMessage(body)
        val sent = messageUri?.let { sentIntent(context, it) }
        if (parts.size <= 1) {
            mgr.sendTextMessage(address, null, body, sent, null)
        } else {
            // One result per part; the same intent for each. FAILED sticks: a
            // later part that succeeds only moves an OUTBOX row (see [recordResult]).
            mgr.sendMultipartTextMessage(address, null, parts, sent?.let { s -> ArrayList(parts.map { s }) }, null)
        }
    }

    /**
     * Explicit target and immutable: this only needs the result code, and the
     * intent must not be redirectable (CLAUDE.md §12).
     */
    private fun sentIntent(context: Context, messageUri: Uri): PendingIntent {
        val intent = Intent(SmsSentReceiver.ACTION_SMS_SENT)
            .setClassName(context.packageName, SmsSentReceiver::class.java.name)
            .setData(messageUri)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Applies the radio's verdict to the row. A part failure is never overwritten by a later success. */
    fun recordResult(context: Context, messageUri: Uri, resultCode: Int) {
        try {
            if (resultCode == Activity.RESULT_OK) {
                update(context, messageUri, MessageType.SENT, null, "${Telephony.Sms.TYPE} = ?", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            } else {
                update(
                    context, messageUri, MessageType.FAILED, resultCode,
                    "${Telephony.Sms.TYPE} IN (?, ?)", Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_SENT,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not record send result $resultCode for $messageUri", e)
        }
    }

    fun setType(context: Context, messageUri: Uri, type: MessageType, errorCode: Int? = null) {
        try {
            update(context, messageUri, type, errorCode, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set type $type for $messageUri", e)
        }
    }

    private fun update(context: Context, uri: Uri, type: MessageType, errorCode: Int?, where: String?, vararg whereArgs: Int) {
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, TelephonyMapper.providerType(type))
            // Always written: a retried message must not keep the last failure's code.
            put(Telephony.Sms.ERROR_CODE, errorCode ?: NO_ERROR)
        }
        context.contentResolver.update(uri, values, where, whereArgs.map { it.toString() }.toTypedArray().takeIf { where != null })
    }

    fun rowUri(messageId: Long): Uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId)
}

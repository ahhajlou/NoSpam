// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.telephony.SendRetryPolicy.Decision
import com.nospam.nospam.core.telephony.SendRetryPolicy.Entry
import com.nospam.nospam.core.telephony.receiver.SendRetryReceiver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends a message again after a failure that can clear by itself (no service,
 * radio off), following [SendRetryPolicy], and turns a send that never reports
 * back into a failure instead of an endless "Sending…".
 *
 * A message waiting for its next attempt is stored with the provider's own
 * QUEUED type, which the thread shows as "Waiting for signal…"; it goes back to
 * OUTBOX for each attempt, and ends SENT, or FAILED for a manual retry.
 *
 * The per-message state (window start, attempt, SIM, delivery report) lives in a
 * private SharedPreferences file: it is scheduling state of this module, not a
 * user setting, and must survive the process, as must the [AlarmManager] alarm
 * that wakes [SendRetryReceiver]. Besides the alarm, an attempt is made at once
 * when service returns ([watchServiceState], Android 12+, while the process
 * lives) and when an SMS arrives, which proves it has ([runDue] with force).
 */
internal object SendRetry {
    private const val TAG = "SendRetry"
    private const val PREFS = "nospam_send_retry"
    const val ACTION_RETRY = "com.nospam.nospam.core.telephony.SEND_RETRY"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun entries(context: Context): Map<Long, Entry> =
        prefs(context).all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val e = (v as? String)?.let(Entry::decode) ?: return@mapNotNull null
            id to e
        }.toMap()

    @Synchronized
    private fun put(context: Context, rowId: Long, entry: Entry) {
        prefs(context).edit().putString(rowId.toString(), entry.encode()).apply()
    }

    @Synchronized
    private fun remove(context: Context, rowId: Long) {
        prefs(context).edit().remove(rowId.toString()).apply()
    }

    /**
     * [SmsSender] handed the message in row [rowId] to the radio. A retry keeps
     * its window and counts one more attempt; any other send starts afresh, so a
     * manual retry gets a full window of its own.
     */
    @Synchronized
    fun onSent(context: Context, rowId: Long, subscriptionId: Int?, deliveryReport: Boolean, fromRetry: Boolean, now: Long) {
        val previous = entries(context)[rowId]
        val entry = if (fromRetry && previous != null) {
            previous.copy(attempt = previous.attempt + 1, lastSentAt = now, nextAt = 0L)
        } else {
            Entry(subscriptionId, deliveryReport, lastSentAt = now)
        }
        put(context, rowId, entry)
        scheduleNext(context)
    }

    /** Nothing more to do for row [rowId]: it went out, or it failed for good. */
    @Synchronized
    fun forget(context: Context, rowId: Long) {
        remove(context, rowId)
        scheduleNext(context)
    }

    /**
     * A temporary failure for row [rowId]. Returns true when the message now
     * waits for another attempt (or already did); false when the caller should
     * mark it failed: its window is over, or this module never sent it.
     */
    @Synchronized
    fun onTemporaryFailure(context: Context, messageUri: Uri, rowId: Long, resultCode: Int, now: Long): Boolean {
        val entry = entries(context)[rowId] ?: return false
        return when (val decision = SendRetryPolicy.onTemporaryFailure(entry, now)) {
            is Decision.Retry -> {
                put(context, rowId, decision.entry)
                SmsSender.setType(context, messageUri, MessageType.QUEUED, resultCode)
                scheduleNext(context)
                true
            }
            Decision.AlreadyWaiting -> true
            Decision.GiveUp -> {
                remove(context, rowId)
                scheduleNext(context)
                false
            }
        }
    }

    /**
     * Sends whatever is due, gives up on sends that never reported back, and
     * re-arms the alarm. [force] sends every waiting message now: service is
     * known to be back.
     */
    @Synchronized
    fun runDue(context: Context, force: Boolean, now: Long = System.currentTimeMillis()) {
        val tracked = entries(context)
        for ((rowId, entry) in tracked) {
            val uri = SmsSender.rowUri(rowId)
            val row = queryRow(context, uri)
            when {
                row == null -> remove(context, rowId)
                row.type == Telephony.Sms.MESSAGE_TYPE_QUEUED -> if (force || entry.nextAt <= now) {
                    try {
                        SmsSender.setType(context, uri, MessageType.OUTBOX)
                        SmsSender.send(
                            context, row.address, row.body, entry.subscriptionId, uri,
                            SendOptions(deliveryReport = entry.deliveryReport), fromRetry = true,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry of $rowId failed to start", e)
                        SmsSender.setType(context, uri, MessageType.FAILED)
                        remove(context, rowId)
                    }
                }
                row.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX -> if (SendRetryPolicy.isStuck(entry.lastSentAt, now)) {
                    // Handed to the radio, never reported back. It may have gone
                    // out, so it is not sent again behind the user's back.
                    SmsSender.setType(context, uri, MessageType.FAILED)
                    remove(context, rowId)
                }
                else -> remove(context, rowId)
            }
        }
        failUntracked(context, tracked.keys, now)
        scheduleNext(context)
    }

    /**
     * OUTBOX or QUEUED rows this module is not tracking (sent before this
     * existed, or its state was lost) and old enough to be stuck become failures
     * rather than "Sending…" forever.
     */
    private fun failUntracked(context: Context, tracked: Set<Long>, now: Long) {
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.TYPE} IN (?, ?) AND ${Telephony.Sms.DATE} < ?",
                arrayOf(
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString(),
                    Telephony.Sms.MESSAGE_TYPE_QUEUED.toString(),
                    (now - SendRetryPolicy.STUCK_AFTER_MS).toString(),
                ),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id !in tracked) SmsSender.setType(context, SmsSender.rowUri(id), MessageType.FAILED)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check for stuck sends", e)
        }
    }

    private class Row(val type: Int, val address: String, val body: String)

    private fun queryRow(context: Context, uri: Uri): Row? = try {
        context.contentResolver.query(
            uri, arrayOf(Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(1)) Row(c.getInt(0), c.getString(1), c.getString(2).orEmpty()) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $uri", e)
        null
    }

    /** One alarm for the earliest due time; none when nothing is pending. */
    private fun scheduleNext(context: Context) {
        val next = entries(context).values.minOfOrNull { e ->
            if (e.nextAt > 0) e.nextAt else e.lastSentAt + SendRetryPolicy.STUCK_AFTER_MS
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_RETRY).setClassName(context.packageName, SendRetryReceiver::class.java.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (next == null) {
            am.cancel(pending)
        } else {
            // Inexact on purpose: exact alarms need a special permission, and a
            // retry a little late is fine. Allowed while idle, so Doze cannot
            // hold a waiting message for hours.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }

    private val watching = AtomicBoolean(false)
    private val worker by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Retries at once when the phone reports service again. Android 12+ only,
     * and only while the process lives; the alarm covers the rest.
     */
    fun watchServiceState(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        try {
            tm.registerTelephonyCallback(worker, object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                override fun onServiceStateChanged(serviceState: ServiceState) {
                    if (serviceState.state == ServiceState.STATE_IN_SERVICE && entries(app).isNotEmpty()) {
                        runDue(app, force = true)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch service state; alarms still retry", e)
            watching.set(false)
        }
    }

    /** Runs [runDue] off the caller's thread. */
    fun runDueAsync(context: Context, force: Boolean, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            try {
                runDue(app, force)
            } finally {
                onDone()
            }
        }
    }
}

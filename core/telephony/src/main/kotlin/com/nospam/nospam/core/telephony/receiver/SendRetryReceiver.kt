// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nospam.nospam.core.telephony.SendRetry

/**
 * Woken by [SendRetry]'s alarm: sends waiting messages that are due and gives up
 * on sends that never reported back.
 *
 * Not exported: the only sender is the system, through the PendingIntent
 * [SendRetry] builds naming this class. Any other action is ignored.
 */
class SendRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SendRetry.ACTION_RETRY) return
        val pending = goAsync()
        SendRetry.runDueAsync(context, force = false) { pending.finish() }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cancels a running history scan from the progress notification. `exported=false`
 * (no external caller may reach it) and it validates its action before acting
 * (CLAUDE.md §16).
 */
class BackfillCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_BACKFILL) return
        val container = (context.applicationContext as? NoSpamApplication)?.container ?: return
        container.spamBackfill.cancel()
    }

    companion object {
        const val ACTION_CANCEL_BACKFILL = "com.nospam.nospam.action.CANCEL_BACKFILL"

        fun cancelPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BackfillCancelReceiver::class.java).apply {
                action = ACTION_CANCEL_BACKFILL
            }
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.core.model.TelephonyConstants
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A conversation notification's "Mark as read" action, and its swipe-away:
 * the messages it listed are then not listed again (see
 * [com.nospam.nospam.core.data.MessageNotificationRepository]). Not exported;
 * it checks its action and thread id before acting (CLAUDE.md §12).
 */
class MessageNotificationReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_MARK_READ && action != ACTION_DISMISSED) return
        val threadId = intent.getLongExtra(TelephonyConstants.EXTRA_THREAD_ID, -1L).takeIf { it >= 0 } ?: return
        val container = (context.applicationContext as? NoSpamApplication)?.container ?: return
        val pending = goAsync()
        scope.launch {
            try {
                if (action == ACTION_MARK_READ) {
                    container.conversationsRepository.setRead(ThreadId(threadId), read = true)
                    NotificationHelper.cancelNotification(context, threadId)
                } else {
                    container.messageNotifications.markSeen(ThreadId(threadId))
                }
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MessageNotification"
        const val ACTION_MARK_READ = "com.nospam.nospam.action.MARK_READ"
        const val ACTION_DISMISSED = "com.nospam.nospam.action.NOTIFICATION_DISMISSED"

        fun markReadIntent(context: Context, threadId: Long): PendingIntent = pendingIntent(context, ACTION_MARK_READ, threadId)

        fun dismissIntent(context: Context, threadId: Long): PendingIntent = pendingIntent(context, ACTION_DISMISSED, threadId)

        // One per thread and action: the request code tells threads apart, the action the two kinds.
        private fun pendingIntent(context: Context, action: String, threadId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                NotificationHelper.notificationId(threadId),
                Intent(context, MessageNotificationReceiver::class.java)
                    .setAction(action)
                    .putExtra(TelephonyConstants.EXTRA_THREAD_ID, threadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.core.model.TelephonyConstants
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.notifications.NotificationHelper
import com.nospam.nospam.core.notifications.R as NotificationsR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A conversation notification's "Mark as read" and "Copy 123456" actions, and
 * its swipe-away: the messages it listed are then not listed again (see
 * [com.nospam.nospam.core.data.MessageNotificationRepository]). Not exported;
 * it checks its action, thread id and code before acting (CLAUDE.md §12).
 */
class MessageNotificationReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_COPY_CODE) {
            intent.getStringExtra(EXTRA_CODE)?.takeIf { CODE.matches(it) }?.let { copyCode(context, it) }
            return
        }
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

    /**
     * Marked sensitive, so the clipboard preview of Android 13+ hides it, as it
     * does a password. That preview is also the confirmation there; older
     * versions show none, so a toast says it. The notification stays: the code
     * may be needed again before the conversation is read.
     */
    private fun copyCode(context: Context, code: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(context.getString(NotificationsR.string.clipboard_label_code), code)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        runCatching { clipboard.setPrimaryClip(clip) }.onFailure {
            Log.w(TAG, "Copying the code failed", it)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, NotificationsR.string.notification_code_copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MessageNotification"
        const val ACTION_MARK_READ = "com.nospam.nospam.action.MARK_READ"
        const val ACTION_DISMISSED = "com.nospam.nospam.action.NOTIFICATION_DISMISSED"
        const val ACTION_COPY_CODE = "com.nospam.nospam.action.COPY_CODE"
        private const val EXTRA_CODE = "com.nospam.nospam.extra.CODE"

        /** `ClipDescription.EXTRA_IS_SENSITIVE`, API 33; older versions ignore it. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** What [com.nospam.nospam.core.model.findOneTimeCode] returns. */
        private val CODE = Regex("[0-9]{4,8}")

        fun markReadIntent(context: Context, threadId: Long): PendingIntent = pendingIntent(context, ACTION_MARK_READ, threadId)

        fun dismissIntent(context: Context, threadId: Long): PendingIntent = pendingIntent(context, ACTION_DISMISSED, threadId)

        fun copyCodeIntent(context: Context, threadId: Long, code: String): PendingIntent =
            pendingIntent(context, ACTION_COPY_CODE, threadId) { putExtra(EXTRA_CODE, code) }

        // One per thread and action: the request code tells threads apart, the action the kinds.
        private fun pendingIntent(
            context: Context,
            action: String,
            threadId: Long,
            extras: Intent.() -> Unit = {},
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                NotificationHelper.notificationId(threadId),
                Intent(context, MessageNotificationReceiver::class.java)
                    .setAction(action)
                    .putExtra(TelephonyConstants.EXTRA_THREAD_ID, threadId)
                    .apply(extras),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

package com.nospam.nospam

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.nospam.nospam.core.data.BackfillStatus
import com.nospam.nospam.core.data.SpamBackfillUseCase
import com.nospam.nospam.core.notifications.NotificationHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges the backfill [BackfillStatus] StateFlow to a low-importance progress
 * notification. Lives in :app so the collector can reach [BackfillCancelReceiver]
 * without creating a core-to-core edge (CLAUDE.md §4). Silently no-ops when
 * notifications are disabled (API 33+ without POST_NOTIFICATIONS).
 */
class BackfillProgressNotifier(
    private val context: Context,
    private val backfill: SpamBackfillUseCase,
    private val scope: CoroutineScope,
    private val notificationManagerCompat: NotificationManagerCompat = NotificationManagerCompat.from(context),
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            backfill.status.collect { status -> render(status) }
        }
    }

    private fun render(status: BackfillStatus) {
        if (!notificationManagerCompat.areNotificationsEnabled()) return
        when (status) {
            is BackfillStatus.Running -> show(status.processed, status.total)
            BackfillStatus.Idle -> Unit
            BackfillStatus.Done,
            BackfillStatus.Cancelled,
            BackfillStatus.Failed,
            -> notificationManagerCompat.cancel(NotificationHelper.NOTIFICATION_ID_BACKFILL)
        }
    }

    private fun show(processed: Int, total: Int) {
        val notification = NotificationHelper.buildBackfillProgressNotification(
            context, processed, total,
            BackfillCancelReceiver.cancelPendingIntent(context)
        )
        notificationManagerCompat.notify(NotificationHelper.NOTIFICATION_ID_BACKFILL, notification)
    }
}
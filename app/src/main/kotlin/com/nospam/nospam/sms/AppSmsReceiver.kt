// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.sms

import kotlinx.coroutines.flow.first
import com.nospam.nospam.core.notifications.incomingAlert
import com.nospam.nospam.core.notifications.IncomingAlert
import com.nospam.nospam.core.notifications.LATE_ALERT_WINDOW_MS
import com.nospam.nospam.core.notifications.lateAlert
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationManagerCompat
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.core.designsystem.component.ContactPhotoLoader
import com.nospam.nospam.core.designsystem.component.avatarBitmap
import com.nospam.nospam.core.designsystem.component.circleAvatarBitmap
import com.nospam.nospam.core.model.NotificationDecision
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.notifications.NotificationHelper
import com.nospam.nospam.core.notifications.NotificationLine
import com.nospam.nospam.core.data.MessageNotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single SMS_DELIVER entry point (see AndroidManifest). Classifies and routes
 * via [com.nospam.nospam.core.data.SmsIngressUseCase], then posts a
 * MessagingStyle notification for ham. Suspected spam that stays in the inbox
 * gets a quiet one on the low-importance channel only when the user turned
 * that on; spam routed to Spam never notifies. A message kept silent because
 * its verdict was late is announced when the verdict lands, if it turned out
 * real ([lateAlert]).
 */
class AppSmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].displayOriginatingAddress
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isEmpty()) return
        // Arrival time on this device, not the carrier's timestamp: that one is
        // truncated to whole seconds and stamped by another clock, so a reply
        // could sort before the message it answers (a same-phone SIM1->SIM2
        // message landed 202ms *before* its own sent row).
        val timestamp = System.currentTimeMillis()
        val subscriptionId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        ).takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        val pending = goAsync()
        scope.launch {
            val app = context.applicationContext as? NoSpamApplication
            var result: com.nospam.nospam.core.data.SmsIngressUseCase.Result? = null
            var atArrival = IncomingAlert.NONE
            try {
                if (app == null) {
                    Log.e(TAG, "NoSpamApplication missing")
                    return@launch
                }
                val handled = app.container.smsIngress.handle(
                    RawMessage(
                        sender = sender,
                        body = body,
                        timestamp = timestamp,
                        subscriptionId = subscriptionId,
                    )
                )
                result = handled
                Log.d(TAG, "Prediction: ${if (handled.isSpam) "spam" else "ham"} (Score: ${handled.score}) state=${handled.senderState} notif=${handled.notificationDecision}")
                if (handled.messageId != null) {
                    atArrival = announce(context, app, handled, handled.notificationDecision, subscriptionId, timestamp)
                }
                // Housekeeping after the message is announced, never before it.
                // An SMS just arrived, so there is service: send anything waiting for it.
                runCatching { app.container.telephony.runSendRetries(serviceIsBack = true) }
                app.container.smsIngress.pruneExpiredVerdicts()
            } catch (e: Exception) {
                Log.e(TAG, "SMS ingress failed", e)
            } finally {
                pending.finish()
            }
            // Earlier messages this process may have left without a verdict.
            app?.container?.classifyMissedMessages()
            // The verdict missed the budget: wait for it outside the broadcast, and
            // announce a message kept silent that turned out to be real. Best effort:
            // the process may be frozen or killed before it lands.
            val handled = result ?: return@launch
            val late = handled.lateDecision ?: return@launch
            if (app == null || handled.messageId == null) return@launch
            try {
                val decision = withTimeoutOrNull(LATE_ALERT_WINDOW_MS) { late.await() } ?: return@launch
                announce(context, app, handled, decision, subscriptionId, timestamp) { alert ->
                    lateAlert(atArrival, alert, elapsedMillis = System.currentTimeMillis() - timestamp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Late announcement failed", e)
            }
        }
    }

    /**
     * Announces [result] as [decision] asks: a notification, a quiet one, the
     * in-app sound, or nothing. [gate] can turn the alert down, for a late one.
     * Returns what was done.
     */
    private suspend fun announce(
        context: Context,
        app: NoSpamApplication,
        result: com.nospam.nospam.core.data.SmsIngressUseCase.Result,
        decision: NotificationDecision,
        subscriptionId: Int?,
        timestamp: Long,
        gate: (IncomingAlert) -> IncomingAlert = { it },
    ): IncomingAlert {
        val container = app.container
        val alert = gate(
            incomingAlert(
                decision = decision,
                threadId = result.threadId.value,
                visibleThreadId = container.visibleThread.value,
                soundsEnabled = container.settingsRepository.messageSounds.first(),
                notifySuspectedSpam = container.settingsRepository.notifySuspectedSpam.first(),
            )
        )
        when (alert) {
            IncomingAlert.NOTIFY, IncomingAlert.NOTIFY_QUIET -> {
                // Never at the cost of the notification itself.
                val avatar = runCatching { senderAvatar(context, container.contactPhotos, result) }
                    .onFailure { Log.w(TAG, "Sender avatar failed", it) }
                    .getOrNull()
                postNotification(
                    context.applicationContext, app, result, subscriptionId, timestamp,
                    suspected = alert == IncomingAlert.NOTIFY_QUIET, avatar = avatar,
                )
            }
            IncomingAlert.IN_APP_SOUND -> container.messageSounds.playReceived()
            IncomingAlert.NONE -> Unit
        }
        return alert
    }

    /**
     * [suspected]: spam that stays in the inbox, posted quietly and labeled.
     * Lists the conversation's other unread messages above this one, as Google
     * Messages does; this message is always shown, even when flagged.
     */
    private suspend fun postNotification(
        context: Context,
        app: NoSpamApplication,
        result: com.nospam.nospam.core.data.SmsIngressUseCase.Result,
        subscriptionId: Int?,
        timestamp: Long,
        suspected: Boolean,
        avatar: Bitmap?,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val unread = runCatching {
            app.container.messageNotifications.unread(result.threadId, includeFlagged = result.contact != null)
        }.onFailure { Log.w(TAG, "Unread messages for the notification failed", it) }.getOrNull()
        val listed = unread?.messages.orEmpty()
        val thisListed = listed.any { it.id.value == result.messageId }
        val earlier = listed.filter { it.id.value != result.messageId }
            .takeLast(MessageNotificationRepository.MAX_MESSAGES - 1)
            .map { NotificationLine(it.body, it.date) }
        val unreadCount = (unread?.unreadCount ?: 0) + if (thisListed) 0 else 1
        val threadId = result.threadId.value
        val notification = NotificationHelper.buildMessageNotification(
            context = context,
            threadId = result.threadId.value,
            sender = result.sender,
            messageBody = result.body,
            isSpam = suspected,
            subscriptionId = subscriptionId,
            timestamp = timestamp,
            senderName = result.contact?.displayName,
            senderAvatar = avatar,
            earlierMessages = earlier,
            unreadCount = unreadCount,
            markReadIntent = MessageNotificationReceiver.markReadIntent(context, threadId),
            dismissIntent = MessageNotificationReceiver.dismissIntent(context, threadId),
        )
        NotificationManagerCompat.from(context).notify(
            NotificationHelper.notificationId(result.threadId.value),
            notification,
        )
        // Suspected spam is not in the group, so it has no summary.
        if (!suspected) NotificationHelper.notifyMessageSummary(context)
    }

    /**
     * The sender as the inbox draws them: the contact's photo, else the same
     * letter or person avatar. The photo is bounded in time: it is decoration,
     * so a slow contacts provider must not hold back the notification, which
     * then goes out with the letter avatar.
     */
    private suspend fun senderAvatar(
        context: Context,
        photos: ContactPhotoLoader,
        result: com.nospam.nospam.core.data.SmsIngressUseCase.Result,
    ): Bitmap {
        val sizePx = (AVATAR_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val photo = result.contact?.photoUri?.let { uri ->
            withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
                runCatching { photos.load(uri, sizePx)?.asAndroidBitmap() }.getOrNull()
            }
        }
        if (photo != null) return circleAvatarBitmap(photo, sizePx)
        // The system's night mode, not the app's: SystemUI draws the notification.
        val dark = (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return avatarBitmap(context, result.contact?.displayName ?: result.sender, result.sender, sizePx, dark)
    }

    companion object {
        private const val TAG = "AppSmsReceiver"
        /** Larger than any notification draws it; the system scales it down. */
        private const val AVATAR_SIZE_DP = 64
        private const val PHOTO_TIMEOUT_MS = 1_000L
    }
}

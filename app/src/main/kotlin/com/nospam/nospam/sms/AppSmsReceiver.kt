// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.sms

import kotlinx.coroutines.flow.first
import com.nospam.nospam.core.notifications.incomingAlert
import com.nospam.nospam.core.notifications.IncomingAlert
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single SMS_DELIVER entry point (see AndroidManifest). Classifies and routes
 * via [com.nospam.nospam.core.data.SmsIngressUseCase], then posts a
 * MessagingStyle notification for ham. Suspected spam that stays in the inbox
 * gets a quiet one on the low-importance channel only when the user turned
 * that on; spam routed to Spam never notifies.
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
            try {
                val app = context.applicationContext as? NoSpamApplication
                if (app == null) {
                    Log.e(TAG, "NoSpamApplication missing")
                    return@launch
                }
                val result = app.container.smsIngress.handle(
                    RawMessage(
                        sender = sender,
                        body = body,
                        timestamp = timestamp,
                        subscriptionId = subscriptionId,
                    )
                )
                Log.d(TAG, "Prediction: ${if (result.isSpam) "spam" else "ham"} (Score: ${result.score}) state=${result.senderState} notif=${result.notificationDecision}")
                // An SMS just arrived, so there is service: send anything waiting for it.
                runCatching { app.container.telephony.runSendRetries(serviceIsBack = true) }
                if (result.messageId != null) {
                    val container = app.container
                    val alert = incomingAlert(
                        decision = result.notificationDecision,
                        threadId = result.threadId.value,
                        visibleThreadId = container.visibleThread.value,
                        soundsEnabled = container.settingsRepository.messageSounds.first(),
                        notifySuspectedSpam = container.settingsRepository.notifySuspectedSpam.first(),
                    )
                    when (alert) {
                        IncomingAlert.NOTIFY -> postNotification(context.applicationContext, result, subscriptionId, timestamp, suspected = false)
                        IncomingAlert.NOTIFY_QUIET -> postNotification(context.applicationContext, result, subscriptionId, timestamp, suspected = true)
                        IncomingAlert.IN_APP_SOUND -> container.messageSounds.playReceived()
                        IncomingAlert.NONE -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS ingress failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** [suspected]: spam that stays in the inbox, posted quietly and labelled. */
    private fun postNotification(
        context: Context,
        result: com.nospam.nospam.core.data.SmsIngressUseCase.Result,
        subscriptionId: Int?,
        timestamp: Long,
        suspected: Boolean,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationHelper.buildMessageNotification(
            context = context,
            threadId = result.threadId.value,
            sender = result.sender,
            messageBody = result.body,
            isSpam = suspected,
            subscriptionId = subscriptionId,
            timestamp = timestamp,
        )
        NotificationManagerCompat.from(context).notify(
            NotificationHelper.notificationId(result.threadId.value),
            notification,
        )
    }

    companion object {
        private const val TAG = "AppSmsReceiver"
    }
}

package com.example.nospam.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.nospam.NoSpamApplication
import com.example.nospam.core.model.RawMessage
import com.example.nospam.core.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single SMS_DELIVER entry point (see AndroidManifest). Classifies via
 * [com.example.nospam.core.data.SmsIngressUseCase], inserts with READ=1 for
 * spam, stores the verdict, then posts a MessagingStyle notification for ham
 * only. Spam is silent apart from the low-importance channel summary.
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
        val timestamp = messages[0].timestampMillis
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
                Log.d(TAG, "Prediction: ${if (result.isSpam) "spam" else "ham"} (Score: ${result.score})")
                if (!result.isSpam && result.messageId != null) {
                    postHamNotification(context.applicationContext, result, subscriptionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS ingress failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun postHamNotification(
        context: Context,
        result: com.example.nospam.core.data.SmsIngressUseCase.Result,
        subscriptionId: Int?,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationHelper.buildMessageNotification(
            context = context,
            threadId = result.threadId.value,
            sender = result.sender,
            messageBody = result.body,
            isSpam = false,
            subscriptionId = subscriptionId,
        )
        NotificationManagerCompat.from(context).notify(
            result.threadId.value.toInt(),
            notification,
        )
    }

    companion object {
        private const val TAG = "AppSmsReceiver"
    }
}

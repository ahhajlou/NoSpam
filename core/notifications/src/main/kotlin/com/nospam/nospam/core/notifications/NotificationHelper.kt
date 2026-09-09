package com.nospam.nospam.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.nospam.nospam.core.model.TelephonyConstants

object NotificationHelper {
    const val CHANNEL_ID_MESSAGES = "messages"
    const val CHANNEL_ID_SPAM = "spam"
    const val CHANNEL_ID_BACKFILL = "backfill"
    const val KEY_TEXT_REPLY = TelephonyConstants.KEY_TEXT_REPLY
    const val REQUEST_CODE_REPLY = 1001
    const val NOTIFICATION_ID_BACKFILL = 4001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                context.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_messages_desc) }
            val spamChannel = NotificationChannel(
                CHANNEL_ID_SPAM,
                context.getString(R.string.channel_spam),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.channel_spam_desc) }
            val backfillChannel = NotificationChannel(
                CHANNEL_ID_BACKFILL,
                context.getString(R.string.channel_backfill),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.channel_backfill_desc) }
            manager.createNotificationChannels(listOf(messagesChannel, spamChannel, backfillChannel))
        }
    }

    fun cancelNotification(context: Context, threadId: Long) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(threadId.toInt())
    }

    /**
     * Ongoing progress for the one-time history scan. [cancelPending] targets a
     * cancel receiver owned by the app (core:notifications must not know it).
     */
    fun buildBackfillProgressNotification(
        context: Context,
        processed: Int,
        total: Int,
        cancelPending: PendingIntent,
    ): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID_BACKFILL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.backfill_title))
            .setContentText(context.getString(R.string.backfill_progress, processed, total))
            .setProgress(total, processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.backfill_cancel), cancelPending)
            .build()
    }

    fun buildMessageNotification(
        context: Context,
        threadId: Long,
        sender: String,
        messageBody: String,
        isSpam: Boolean = false,
        subscriptionId: Int? = null,
    ): android.app.Notification {
        val channelId = if (isSpam) CHANNEL_ID_SPAM else CHANNEL_ID_MESSAGES
        val person = Person.Builder().setName(sender).setKey(sender).build()
        val style = NotificationCompat.MessagingStyle(person)
            .addMessage(messageBody, System.currentTimeMillis(), person)

        val replyIntent = Intent(TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE).apply {
            setClassName(context.packageName, "com.nospam.nospam.core.telephony.service.HeadlessSmsSendService")
            data = android.net.Uri.fromParts("sms", sender, null)
            putExtra("thread_id", threadId)
            if (subscriptionId != null) putExtra("subscription_id", subscriptionId)
        }
        val replyPending = PendingIntent.getService(
            context, REQUEST_CODE_REPLY + threadId.toInt(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Reply").build()
        val replyAction = NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.ic_menu_send),
            "Reply",
            replyPending
        ).addRemoteInput(remoteInput).build()

        // Content intent deep-links to thread
        val contentIntent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(context.packageName, "com.nospam.nospam.MainActivity")
            data = android.net.Uri.fromParts("sms", sender, null)
            putExtra("thread_id", threadId)
            putExtra("android.intent.extra.TEXT", messageBody)
        }
        val contentPending = PendingIntent.getActivity(
            context, threadId.toInt(), contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dynamic shortcut for Conversations bubble/shortcut
        try {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, "thread-$threadId")
                .setShortLabel(sender)
                .setLongLabel(sender)
                .setIntent(contentIntent)
                .setPerson(person)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (_: Exception) {}

        val now = System.currentTimeMillis()
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentPending)
            .setWhen(now)
            .setShowWhen(true)
            .setShortcutId("thread-$threadId")
            .addAction(replyAction)
            .setAutoCancel(true)
            .build()
    }
}

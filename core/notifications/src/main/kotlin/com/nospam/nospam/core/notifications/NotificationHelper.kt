// SPDX-License-Identifier: GPL-3.0-or-later

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
import com.nospam.nospam.core.model.isAlphanumericSender

object NotificationHelper {
    /**
     * The messages channel. A channel's importance cannot be raised by the app
     * once it exists, and the first one ("messages") was created at DEFAULT,
     * which never pops up on screen. Hence a new id at HIGH; see
     * [createChannels] for what happens to the old one.
     */
    const val CHANNEL_ID_MESSAGES = "messages_v2"
    internal const val LEGACY_CHANNEL_ID_MESSAGES = "messages"
    const val CHANNEL_ID_SPAM = "spam"
    const val CHANNEL_ID_BACKFILL = "backfill"
    const val KEY_TEXT_REPLY = TelephonyConstants.KEY_TEXT_REPLY
    const val REQUEST_CODE_REPLY = 1001
    const val NOTIFICATION_ID_BACKFILL = 4001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val legacy = manager.getNotificationChannel(LEGACY_CHANNEL_ID_MESSAGES)
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                context.getString(R.string.channel_messages),
                messagesImportance(legacy?.importance),
            ).apply {
                description = context.getString(R.string.channel_messages_desc)
                // Keep what the user set on the old channel. Untouched, these
                // equal the defaults, so copying them changes nothing.
                if (legacy != null) {
                    setSound(legacy.sound, legacy.audioAttributes)
                    // Pattern first: setting a null pattern turns vibration off.
                    legacy.vibrationPattern?.let { vibrationPattern = it }
                    enableVibration(legacy.shouldVibrate())
                    enableLights(legacy.shouldShowLights())
                    lightColor = legacy.lightColor
                    setShowBadge(legacy.canShowBadge())
                    lockscreenVisibility = legacy.lockscreenVisibility
                }
            }
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
            if (legacy != null) manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_MESSAGES)
        }
    }

    /**
     * Importance of the messages channel, given the old channel's when there is
     * one. HIGH (pops up on screen) unless the user had turned the old channel
     * down, which is kept: off stays off, silent stays silent. The old default,
     * DEFAULT, is what the app chose, not the user, so it becomes HIGH.
     */
    internal fun messagesImportance(legacyImportance: Int?): Int = when {
        legacyImportance == null -> NotificationManager.IMPORTANCE_HIGH
        legacyImportance < NotificationManager.IMPORTANCE_DEFAULT -> legacyImportance
        else -> NotificationManager.IMPORTANCE_HIGH
    }

    /**
     * Notification id for a thread. `toInt()` truncated the provider's `Long`
     * thread id, so two threads could collide onto one notification; `hashCode()`
     * folds the high bits in instead. Every notify/cancel must go through this so
     * the ids stay in agreement.
     */
    fun notificationId(threadId: Long): Int = threadId.hashCode()

    fun cancelNotification(context: Context, threadId: Long) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId(threadId))
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
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.backfill_title))
            .setContentText(context.getString(R.string.backfill_progress, processed, total))
            // Before the first message is processed show an indeterminate spinner so
            // the bar reads "starting…" instead of "stuck at 0".
            .setProgress(if (processed == 0) 0 else total, processed, processed == 0)
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
        /** When the message was sent. Defaults to now for callers without one. */
        timestamp: Long = System.currentTimeMillis(),
    ): android.app.Notification {
        // isSpam: a message that looks like spam but stays in the inbox, posted
        // on the low-importance channel and labelled so it reads as a warning.
        val channelId = if (isSpam) CHANNEL_ID_SPAM else CHANNEL_ID_MESSAGES
        val person = Person.Builder().setName(sender).setKey(sender).build()
        val style = NotificationCompat.MessagingStyle(person)
            .addMessage(messageBody, timestamp, person)

        val replyIntent = Intent(TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE).apply {
            setClassName(context.packageName, "com.nospam.nospam.core.telephony.service.HeadlessSmsSendService")
            data = android.net.Uri.fromParts("sms", sender, null)
            putExtra(TelephonyConstants.EXTRA_THREAD_ID, threadId)
            if (subscriptionId != null) putExtra(TelephonyConstants.EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }
        val replyPending = PendingIntent.getService(
            context, REQUEST_CODE_REPLY + notificationId(threadId), replyIntent,
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
            putExtra(TelephonyConstants.EXTRA_THREAD_ID, threadId)
            putExtra("android.intent.extra.TEXT", messageBody)
        }
        val contentPending = PendingIntent.getActivity(
            context, notificationId(threadId), contentIntent,
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

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentPending)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setShortcutId("thread-$threadId")
            // A sender ID cannot receive a reply, so it is not offered.
            .apply { if (!isAlphanumericSender(sender)) addAction(replyAction) }
            .setAutoCancel(true)
            .apply { if (isSpam) setSubText(context.getString(R.string.notification_suspected_spam)) }
            .build()
    }
}

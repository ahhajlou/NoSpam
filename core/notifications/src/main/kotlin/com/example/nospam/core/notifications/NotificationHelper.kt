package com.example.nospam.core.notifications

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
import com.example.nospam.core.model.TelephonyConstants

object NotificationHelper {
    const val CHANNEL_ID_MESSAGES = "messages"
    const val CHANNEL_ID_SPAM = "spam"
    const val KEY_TEXT_REPLY = "key_text_reply"
    const val REQUEST_CODE_REPLY = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Incoming SMS/MMS" }
            val spamChannel = NotificationChannel(
                CHANNEL_ID_SPAM,
                "Spam",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Blocked spam" }
            manager.createNotificationChannels(listOf(messagesChannel, spamChannel))
        }
    }

    fun buildMessageNotification(
        context: Context,
        threadId: Long,
        sender: String,
        messageBody: String,
        isSpam: Boolean = false
    ): android.app.Notification {
        val channelId = if (isSpam) CHANNEL_ID_SPAM else CHANNEL_ID_MESSAGES
        val person = Person.Builder().setName(sender).build()
        val style = NotificationCompat.MessagingStyle(person)
            .addMessage(messageBody, System.currentTimeMillis(), person)

        val replyIntent = Intent(TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE).apply {
            `package` = context.packageName
            data = android.net.Uri.parse("sms:$sender")
            putExtra("thread_id", threadId)
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

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction)
            .setAutoCancel(true)
            .build()
    }

    fun buildDirectReplyIntent(context: Context, address: String, threadId: Long): PendingIntent {
        val intent = Intent(TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE).apply {
            `package` = context.packageName
            data = android.net.Uri.parse("sms:$address")
            putExtra(TelephonyConstants.EXTRA_MESSAGE, "")
            putExtra("thread_id", threadId)
        }
        return PendingIntent.getService(
            context, threadId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}

package com.example.nospam.receiver

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.nospam.ml.SpamDetector

class SmsReceiver : BroadcastReceiver() {

    // 1. Singleton holder for the SpamDetector
    companion object {
        @Volatile
        private var detector: SpamDetector? = null

        fun getDetector(context: Context): SpamDetector {
            // Double-checked locking for thread safety
            return detector ?: synchronized(this) {
                detector ?: SpamDetector(context).also { detector = it }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 2. Get the singleton instance (loads from disk ONLY on the very first SMS)
        val detector = getDetector(context)

        // 3. Verify this is the correct intent action
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {

            // 4. Extract the SMS messages from the intent
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages != null && messages.isNotEmpty()) {
                val sender = messages[0].displayOriginatingAddress
                val messageBody = messages.joinToString(separator = "") { it.messageBody ?: "" }
                val timestamp = messages[0].timestampMillis

                Log.d("SmsReceiver", "✅ Sender: $sender")
                Log.d("SmsReceiver", "✅ Message: $messageBody")

                // 5. Run the spam detection (Now blazing fast, no disk I/O!)
                val (label, score) = detector.predict(messageBody)
                Log.d("SmsReceiver", "🤖 Prediction: $label (Score: $score)")

                // Optional: Auto-mark as read if it's spam to prevent annoying notifications
                val isRead = if (label == "spam") 1 else 0

                // 6. Save the message to the system SMS Provider
                saveSmsToInbox(context, sender, messageBody, timestamp, isRead)
            }
        }
    }

    private fun saveSmsToInbox(context: Context, sender: String?, body: String, timestamp: Long, isRead: Int) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, isRead) // 0 = Unread, 1 = Read
        }

        try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Log.d("SmsReceiver", "✅ SMS successfully saved to system inbox")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "❌ Failed to save SMS to inbox", e)
        }
    }
}

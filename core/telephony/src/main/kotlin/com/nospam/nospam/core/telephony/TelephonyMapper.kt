package com.nospam.nospam.core.telephony

import android.database.Cursor
import android.provider.Telephony
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId

object TelephonyMapper {
    fun mapCursorToMessage(cursor: Cursor): Message {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
        val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
        val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
        val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
        val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1
        val type = when (typeInt) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageType.INBOX
            Telephony.Sms.MESSAGE_TYPE_SENT -> MessageType.SENT
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageType.DRAFT
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> MessageType.OUTBOX
            Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageType.FAILED
            else -> MessageType.INBOX
        }
        return Message(
            id = MessageId(id),
            threadId = ThreadId(threadId),
            address = address,
            body = body,
            date = date,
            type = type,
            read = read
        )
    }

    /**
     * Builds a [Conversation] from already-loaded messages. Preferred over
     * querying Threads.CONTENT_URI: provider thread columns (snippet,
     * message_count) are not guaranteed across Android versions and can
     * throw SQLiteException on some devices.
     */
    fun toConversation(threadId: ThreadId, messages: List<Message>): Conversation {
        require(messages.isNotEmpty())
        val latest = messages.maxBy { it.date }
        return Conversation(
            threadId = threadId,
            participants = listOf(Participant(address = latest.address)),
            snippet = latest.body,
            date = latest.date,
            messageCount = messages.size,
            read = messages.all { it.read },
        )
    }

    fun mapCursorToConversation(cursor: Cursor): Conversation {
        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Threads._ID))
        val snippet = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Threads.SNIPPET)) ?: ""
        val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Threads.DATE))
        val messageCount = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Threads.MESSAGE_COUNT))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Threads.READ)) == 1
        return Conversation(
            threadId = ThreadId(threadId),
            participants = emptyList(), // resolved via address lookup separately
            snippet = snippet,
            date = date,
            messageCount = messageCount,
            read = read
        )
    }

    fun buildMessageValues(address: String, body: String, date: Long, read: Int, subscriptionId: Int? = null): android.content.ContentValues {
        return android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, read)
            if (subscriptionId != null) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
    }

    fun buildSentValues(address: String, body: String, date: Long, subscriptionId: Int? = null): android.content.ContentValues {
        return android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
            if (subscriptionId != null) {
                put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
        }
    }
}

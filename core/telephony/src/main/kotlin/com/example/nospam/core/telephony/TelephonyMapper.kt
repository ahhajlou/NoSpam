package com.example.nospam.core.telephony

import android.database.Cursor
import android.provider.Telephony
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.MessageId
import com.example.nospam.core.model.MessageType
import com.example.nospam.core.model.Participant
import com.example.nospam.core.model.ThreadId

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

    fun buildMessageValues(address: String, body: String, date: Long, read: Int): android.content.ContentValues {
        return android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, read)
        }
    }
}

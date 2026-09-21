// SPDX-License-Identifier: GPL-3.0-or-later

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
    private fun normalizeDate(millisOrSeconds: Long): Long =
        if (millisOrSeconds in 1 until 1_000_000_0000L) millisOrSeconds * 1000 else millisOrSeconds

    fun mapCursorToMessage(cursor: Cursor): Message {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
        val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
        val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
        val rawDate = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
        val date = normalizeDate(rawDate)
        val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1
        val type = when (typeInt) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageType.INBOX
            Telephony.Sms.MESSAGE_TYPE_SENT -> MessageType.SENT
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageType.DRAFT
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> MessageType.OUTBOX
            Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageType.FAILED
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> MessageType.QUEUED
            else -> MessageType.INBOX
        }
        // Optional: not every projection asks for it.
        val subIndex = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
        val subscriptionId = if (subIndex >= 0 && !cursor.isNull(subIndex)) {
            cursor.getInt(subIndex).takeIf { it >= 0 }
        } else null
        return Message(
            id = MessageId(id),
            threadId = ThreadId(threadId),
            address = address,
            body = body,
            date = date,
            type = type,
            read = read,
            subscriptionId = subscriptionId,
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
        val rawDate = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Threads.DATE))
        val date = normalizeDate(rawDate)
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

    /** Provider type for [type]; the inverse of the mapping in [mapCursorToMessage]. */
    fun providerType(type: MessageType): Int = when (type) {
        MessageType.INBOX -> Telephony.Sms.MESSAGE_TYPE_INBOX
        MessageType.SENT -> Telephony.Sms.MESSAGE_TYPE_SENT
        MessageType.DRAFT -> Telephony.Sms.MESSAGE_TYPE_DRAFT
        MessageType.OUTBOX -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
        MessageType.FAILED -> Telephony.Sms.MESSAGE_TYPE_FAILED
        MessageType.QUEUED -> Telephony.Sms.MESSAGE_TYPE_QUEUED
    }

    /** An outgoing message that has not been handed to the radio yet; see [SmsSender]. */
    fun buildOutboxValues(address: String, body: String, date: Long, subscriptionId: Int? = null): android.content.ContentValues =
        buildSentValues(address, body, date, subscriptionId).apply {
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
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

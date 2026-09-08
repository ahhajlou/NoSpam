package com.nospam.nospam.core.telephony

import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.ThreadId
import kotlinx.coroutines.flow.Flow

interface TelephonyDataSource {
    fun observeConversations(): Flow<List<Conversation>>
    /** Live messages of one thread; re-emits on every provider change. */
    fun observeMessages(threadId: ThreadId): Flow<List<Message>>
    suspend fun getConversations(): List<Conversation>
    suspend fun getMessages(threadId: ThreadId): List<Message>
    suspend fun sendMessage(address: String, body: String, subscriptionId: Int? = null): Result<Unit>
    suspend fun markAsRead(threadId: ThreadId)
    suspend fun markAsUnread(threadId: ThreadId)
    suspend fun deleteConversation(threadId: ThreadId)
    /** Inserts an incoming message into the system inbox. Returns the row id, or null on failure. */
    suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int? = null): Long?
    /** Persists an outgoing message to the sent box (SmsManager never writes it). Null on failure. */
    suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int? = null): Long?
    /** Resolves the thread id for an address, or -1 on failure. */
    suspend fun getOrCreateThreadId(address: String): Long
    suspend fun updateMessageRead(messageId: Long, read: Boolean)
    suspend fun isSystemBlocked(address: String): Boolean
    suspend fun lookupContact(address: String): com.nospam.nospam.core.model.Participant?
    suspend fun hasOutboundMessages(threadId: com.nospam.nospam.core.model.ThreadId): Boolean
}

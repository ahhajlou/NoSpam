package com.example.nospam.core.telephony

import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.ThreadId
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
    suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long?
    /** Persists an outgoing message to the sent box (SmsManager never writes it). Null on failure. */
    suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int? = null): Long?
    /** Resolves the thread id for an address, or -1 on failure. */
    suspend fun getOrCreateThreadId(address: String): Long
}

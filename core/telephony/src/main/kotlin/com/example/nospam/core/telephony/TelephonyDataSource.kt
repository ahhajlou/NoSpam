package com.example.nospam.core.telephony

import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.flow.Flow

interface TelephonyDataSource {
    fun observeConversations(): Flow<List<Conversation>>
    suspend fun getConversations(): List<Conversation>
    suspend fun getMessages(threadId: ThreadId): List<Message>
    suspend fun sendMessage(address: String, body: String, subscriptionId: Int? = null): Result<Unit>
    suspend fun markAsRead(threadId: ThreadId)
    suspend fun deleteConversation(threadId: ThreadId)
}

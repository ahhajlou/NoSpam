package com.example.nospam.core.telephony

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RealTelephonyDataSource(
    private val context: Context
) : TelephonyDataSource {
    override fun observeConversations(): Flow<List<Conversation>> = flow {
        emit(getConversations())
    }

    override suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Conversation>()
        val uri = Telephony.Threads.CONTENT_URI
        val projection = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.SNIPPET,
            Telephony.Threads.DATE,
            Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.READ
        )
        context.contentResolver.query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                list.add(TelephonyMapper.mapCursorToConversation(cursor))
            }
        }
        list
    }

    override suspend fun getMessages(threadId: ThreadId): List<Message> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Message>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )
        val sel = "${Telephony.Sms.THREAD_ID} = ?"
        val args = arrayOf(threadId.value.toString())
        context.contentResolver.query(uri, projection, sel, args, "${Telephony.Sms.DATE} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                list.add(TelephonyMapper.mapCursorToMessage(cursor))
            }
        }
        list
    }

    override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val smsManager = if (subscriptionId != null) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                val subManager = context.getSystemService(SubscriptionManager::class.java)
                val defaultId = SubscriptionManager.getDefaultSubscriptionId()
                if (defaultId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SmsManager.getSmsManagerForSubscriptionId(defaultId)
                } else {
                    SmsManager.getDefault()
                }
            }
            smsManager.sendTextMessage(address, null, body, null, null)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAsRead(threadId: ThreadId) = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
        context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.value.toString())
        )
        Unit
    }

    override suspend fun deleteConversation(threadId: ThreadId) = withContext(Dispatchers.IO) {
        context.contentResolver.delete(
            Telephony.Threads.CONTENT_URI,
            "${Telephony.Threads._ID} = ?",
            arrayOf(threadId.value.toString())
        )
        Unit
    }
}

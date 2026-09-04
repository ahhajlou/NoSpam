package com.example.nospam.core.telephony

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class RealTelephonyDataSource(
    private val context: Context
) : TelephonyDataSource {
    companion object {
        private const val TAG = "RealTelephony"
    }
    /**
     * Emits the inbox on subscribe and re-emits on every provider change
     * (incoming SMS, sent message, read-state update). The ContentObserver
     * lives here — callers only see a cold Flow. Rapid bursts are coalesced
     * by [mapLatest], which cancels an in-flight reload.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeConversations(): Flow<List<Conversation>> =
        observeSmsChanges()
            .onStart { emit(Unit) }
            .mapLatest { getConversations() }

    private fun observeSmsChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer
        )
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    override suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        // Without READ_SMS (or before the app is default) the provider throws
        // SecurityException — surface as an empty list, never crash the UI.
        try {
            queryConversations()
        } catch (e: Exception) {
            // SecurityException (no permission) or SQLiteException (provider
            // column differences) — surface as empty, never crash the UI.
            android.util.Log.w("RealTelephony", "Provider query failed", e)
            emptyList()
        }
    }

    private fun queryConversations(): List<Conversation> {
        // Group raw SMS rows client-side instead of querying Threads.CONTENT_URI:
        // thread columns like `snippet` are not present on all Android versions
        // (SQLiteException: no such column) and we don't support MMS yet anyway.
        val messages = mutableListOf<Message>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(TelephonyMapper.mapCursorToMessage(cursor))
            }
        }
        return messages.groupBy { it.threadId }
            .map { (threadId, threadMessages) -> TelephonyMapper.toConversation(threadId, threadMessages) }
            .sortedByDescending { it.date }
    }

    override suspend fun getMessages(threadId: ThreadId): List<Message> = withContext(Dispatchers.IO) {
        try {
            queryMessages(threadId)
        } catch (e: Exception) {
            // SecurityException (no permission) or SQLiteException (provider
            // column differences) — surface as empty, never crash the UI.
            android.util.Log.w("RealTelephony", "Provider query failed", e)
            emptyList()
        }
    }

    private fun queryMessages(threadId: ThreadId): List<Message> {
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
        return list
    }

    override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.resolveSmsManager(subscriptionId).sendTextMessage(address, null, body, null, null)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAsRead(threadId: ThreadId) = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.value.toString())
            )
        } catch (e: Exception) {
            // Best effort: provider may deny the write when not default app.
            Log.w(TAG, "markAsRead failed", e)
        }
        Unit
    }

    override suspend fun markAsUnread(threadId: ThreadId) = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 0) }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.value.toString())
            )
        } catch (e: Exception) {
            // Best effort: provider may deny the write when not default app.
            Log.w(TAG, "markAsUnread failed", e)
        }
        Unit
    }

    override suspend fun deleteConversation(threadId: ThreadId) = withContext(Dispatchers.IO) {
        try {
            // Delete the message rows (the provider-supported operation); the
            // thread drops out of the conversation list once empty. A Threads
            // delete is attempted best-effort for providers supporting it —
            // Threads.CONTENT_URI is a query UNION on most builds, so deleting
            // there alone is a silent no-op.
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.value.toString())
            )
            runCatching {
                context.contentResolver.delete(
                    ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId.value),
                    null,
                    null,
                )
            }
        } catch (e: Exception) {
            // Best effort: provider denies writes unless this is the default app.
            Log.w(TAG, "deleteConversation failed", e)
        }
        Unit
    }

    override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long? =
        withContext(Dispatchers.IO) {
            try {
                val values = TelephonyMapper.buildMessageValues(address, body, date, if (read) 1 else 0)
                val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                uri?.lastPathSegment?.toLongOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "insertInboxMessage failed", e)
                null
            }
        }

    override suspend fun insertSentMessage(
        address: String,
        body: String,
        date: Long,
        subscriptionId: Int?,
    ): Long? = withContext(Dispatchers.IO) {
        try {
            val values = TelephonyMapper.buildSentValues(address, body, date, subscriptionId)
                val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                uri?.lastPathSegment?.toLongOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "insertSentMessage failed", e)
                null
            }
    }

    override suspend fun getOrCreateThreadId(address: String): Long = withContext(Dispatchers.IO) {
        try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            Log.w(TAG, "getOrCreateThreadId failed", e)
            -1L
        }
    }
}

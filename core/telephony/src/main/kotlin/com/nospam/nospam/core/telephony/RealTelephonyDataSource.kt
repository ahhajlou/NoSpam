package com.nospam.nospam.core.telephony

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RealTelephonyDataSource(
    private val context: Context
) : TelephonyDataSource {
    companion object {
        private const val TAG = "RealTelephony"
    }

    private val contactLookup by lazy { ContactLookup(context) }
    private val conversationCacheMutex = Mutex()
    private var cachedConversations: List<Conversation>? = null
    private var cachedMetas: List<ThreadMeta>? = null
    private var cachedLatestMap: Map<Long, SmsLatest> = emptyMap()

    // ThreadMeta and SmsLatest are shared for cache comparison
    private data class ThreadMeta(val id: Long, val date: Long, val count: Int, val snippet: String, val read: Boolean)
    private data class SmsLatest(val address: String, val body: String, val date: Long)

    /** One consistent read of the three cache fields, taken under the mutex. */
    private data class CacheSnapshot(
        val metas: List<ThreadMeta>?,
        val conversations: List<Conversation>?,
        val latest: Map<Long, SmsLatest>,
    )

    /**
     * Emits the inbox on subscribe and re-emits on every provider change
     * (incoming SMS, sent message, read-state update). The ContentObserver
     * lives here — callers only see a cold Flow. Rapid bursts are coalesced
     * by [mapLatest], which cancels an in-flight reload, and debounced 200ms.
     */
    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    override fun observeConversations(): Flow<List<Conversation>> =
        observeSmsChanges()
            .debounce(200)
            .onStart { emit(Unit) }
            .mapLatest { getConversations() }

    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    override fun observeMessages(threadId: ThreadId): Flow<List<Message>> =
        observeSmsChanges()
            .debounce(200)
            .onStart { emit(Unit) }
            .mapLatest { getMessages(threadId) }

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

    private suspend fun queryConversations(): List<Conversation> {
        // Try fast path: Threads.CONTENT_URI (provider-side group) when available
        tryThreadsQuery()?.let { return it }
        // Fallback: client-side group but LIMITED to 3000 most recent SMS rows
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
            Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC LIMIT 3000"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(TelephonyMapper.mapCursorToMessage(cursor))
            }
        }
        val grouped = messages.groupBy { it.threadId }
        contactLookup.warm()
        // Parallelize contact lookups
        return coroutineScope {
            grouped.map { (threadId, threadMessages) ->
                async(Dispatchers.IO) {
                    val base = TelephonyMapper.toConversation(threadId, threadMessages)
                    val contact = runCatching { contactLookup.lookup(base.participants.first().address) }.getOrNull()
                    if (contact != null) base.copy(participants = listOf(contact), photoUri = contact.photoUri) else base
                }
            }.awaitAll()
        }.sortedByDescending { it.date }
    }

    private suspend fun tryThreadsQuery(): List<Conversation>? {
        return try {
            val proj = arrayOf(
                Telephony.Threads._ID,
                Telephony.Threads.DATE,
                Telephony.Threads.MESSAGE_COUNT,
                Telephony.Threads.SNIPPET,
                Telephony.Threads.READ,
            )
            val metas = mutableListOf<ThreadMeta>()
            context.contentResolver.query(Telephony.Threads.CONTENT_URI, proj, null, null, "${Telephony.Threads.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(Telephony.Threads._ID))
                    val rawDate = try { c.getLong(c.getColumnIndexOrThrow(Telephony.Threads.DATE)) } catch (_: Exception) { 0L }
                    val date = if (rawDate in 1 until 1_000_000_0000L) rawDate * 1000 else rawDate
                    val count = try { c.getInt(c.getColumnIndexOrThrow(Telephony.Threads.MESSAGE_COUNT)) } catch (_: Exception) { 0 }
                    val snippet = try { c.getString(c.getColumnIndexOrThrow(Telephony.Threads.SNIPPET)) ?: "" } catch (_: Exception) { "" }
                    val read = try { c.getInt(c.getColumnIndexOrThrow(Telephony.Threads.READ)) == 1 } catch (_: Exception) { true }
                    metas.add(ThreadMeta(id, date, count, snippet, read))
                }
            }
            if (metas.isEmpty()) return null

            // The three cache fields are one generation and must be read as one.
            // Reading them in separate critical sections let a concurrent writer
            // install a new generation in between, so the validated metas and the
            // returned conversations could come from different snapshots.
            val snapshot = conversationCacheMutex.withLock {
                CacheSnapshot(cachedMetas, cachedConversations, cachedLatestMap)
            }
            val cached = snapshot.metas

            // Cache check: if metas identical to cached, reuse cached conversations without Sms re-query
            if (cached != null && cached == metas) {
                snapshot.conversations?.let { return it }
            }

            // Determine which threadIds actually changed (DATE/count/read/snippet)
            val cachedMap = cached?.associateBy { it.id } ?: emptyMap()
            val changedIds = metas.filter { meta ->
                val prev = cachedMap[meta.id]
                prev == null || prev.date != meta.date || prev.count != meta.count || prev.read != meta.read || prev.snippet != meta.snippet
            }.map { it.id }.toSet()

            // If all metas unchanged, reuse cache
            if (changedIds.isEmpty() && cached != null) {
                return snapshot.conversations
            }

            // Batch Sms lookup only for changed (or all if no cache) to get latest address/body/date
            val idsToQuery = if (cached == null) metas.map { it.id } else changedIds.toList()
            val latestMap = mutableMapOf<Long, SmsLatest>()
            // Also carry over previous latestMap for unchanged threads
            snapshot.latest.let { prevLatest ->
                for (id in metas.map { it.id }) {
                    if (id !in idsToQuery) {
                        prevLatest[id]?.let { latestMap[id] = it }
                    }
                }
            }
            idsToQuery.chunked(400).forEach { chunk ->
                val sel = "${Telephony.Sms.THREAD_ID} IN (${chunk.joinToString(",") { "?" }})"
                val args = chunk.map { it.toString() }.toTypedArray()
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                    sel, args, "${Telephony.Sms.DATE} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val tid = c.getLong(0)
                        if (!latestMap.containsKey(tid)) {
                            val addr = c.getString(1) ?: "Unknown"
                            val body = c.getString(2) ?: ""
                            val rawDate = c.getLong(3)
                            val date = if (rawDate in 1 until 1_000_000_0000L) rawDate * 1000 else rawDate
                            latestMap[tid] = SmsLatest(addr, body, date)
                            // Rows come back DATE DESC, so the first row seen for
                            // a thread is already its newest. Once every thread has
                            // one, the rest of the cursor is older messages we
                            // discard anyway - on this inbox that was ~4000 extra
                            // rows walked across the CursorWindow for nothing.
                            if (latestMap.size >= metas.size) return@use
                        }
                    }
                }
            }
            // Parallelize contact lookups + build
            contactLookup.warm()
            val conversations = coroutineScope {
                metas.mapNotNull { meta ->
                    val latest = latestMap[meta.id] ?: return@mapNotNull null
                    async(Dispatchers.IO) {
                        val participant = runCatching { contactLookup.lookup(latest.address) }.getOrNull() ?: com.nospam.nospam.core.model.Participant(address = latest.address)
                        Conversation(
                            threadId = ThreadId(meta.id),
                            participants = listOf(participant),
                            snippet = latest.body.ifBlank { meta.snippet },
                            date = latest.date,
                            messageCount = meta.count,
                            read = meta.read,
                            photoUri = participant.photoUri
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            if (conversations.isEmpty()) null else {
                // Update cache
                conversationCacheMutex.withLock {
                    cachedMetas = metas.toList()
                    cachedLatestMap = latestMap.toMap()
                    cachedConversations = conversations
                }
                conversations
            }
        } catch (_: Exception) { null }
    }

    override suspend fun getMessages(
        threadId: ThreadId,
        limit: Int,
        beforeId: Long?,
    ): List<Message> = withContext(Dispatchers.IO) {
        try {
            queryMessages(threadId, limit, beforeId)
        } catch (e: Exception) {
            // SecurityException (no permission) or SQLiteException (provider
            // column differences) — surface as empty, never crash the UI.
            android.util.Log.w("RealTelephony", "Provider query failed", e)
            emptyList()
        }
    }

    private fun queryMessages(threadId: ThreadId, limit: Int, beforeId: Long?): List<Message> {
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
        // Backward pagination: newest [limit] rows, or rows strictly older than
        // [beforeId] (exclusive) when scrolling up — never a hard thread truncation.
        val sel = if (beforeId != null) {
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms._ID} < ?"
        } else {
            "${Telephony.Sms.THREAD_ID} = ?"
        }
        val args = if (beforeId != null) {
            arrayOf(threadId.value.toString(), beforeId.toString())
        } else {
            arrayOf(threadId.value.toString())
        }
        // Secondary _ID for stable order on same DATE; LIMIT is an Int constant, never user input.
        context.contentResolver.query(uri, projection, sel, args, "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC LIMIT $limit")?.use { cursor ->
            while (cursor.moveToNext()) {
                list.add(TelephonyMapper.mapCursorToMessage(cursor))
            }
        }
        return list.sortedWith(compareBy({ it.date }, { it.id.value }))
    }

    override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mgr = context.resolveSmsManager(subscriptionId)
            val parts = mgr.divideMessage(body)
            if (parts.size <= 1) {
                mgr.sendTextMessage(address, null, body, null, null)
            } else {
                mgr.sendMultipartTextMessage(address, null, parts, null, null)
            }
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

    override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int?): Long? =
        withContext(Dispatchers.IO) {
            try {
                val values = TelephonyMapper.buildMessageValues(address, body, date, if (read) 1 else 0, subscriptionId)
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

    override suspend fun updateMessageRead(messageId: Long, read: Boolean) = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateMessageRead failed", e)
        }
        Unit
    }

    override suspend fun isSystemBlocked(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val variants = PhoneNumberNormalizer.normalizedVariants(context, address)
            for (v in variants) {
                if (android.provider.BlockedNumberContract.isBlocked(context, v)) return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "isSystemBlocked check failed", e)
            false
        }
    }

    override suspend fun lookupContact(address: String): com.nospam.nospam.core.model.Participant? = withContext(Dispatchers.IO) {
        contactLookup.lookup(address)
    }

    override suspend fun hasOutboundMessages(threadId: ThreadId): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(threadId.value.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
                null
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
    }

    override suspend fun getOutboundSenderAddresses(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val addresses = mutableSetOf<String>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.TYPE} = ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
                }
            }
            addresses
        } catch (e: Exception) {
            Log.w(TAG, "getOutboundSenderAddresses failed", e)
            emptySet()
        }
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun getActiveSubscriptions(): List<TelephonyDataSource.SimInfo> = withContext(Dispatchers.IO) {
        try {
            val sm = context.getSystemService(android.telephony.SubscriptionManager::class.java) ?: return@withContext emptyList()
            val list = sm.activeSubscriptionInfoList ?: return@withContext emptyList()
            list.map { TelephonyDataSource.SimInfo(it.subscriptionId, it.displayName?.toString() ?: "SIM ${it.simSlotIndex+1}", it.number) }
        } catch (_: SecurityException) { emptyList() } catch (_: Exception) { emptyList() }
    }

    override suspend fun searchBodyMatch(query: String): Set<Long> = withContext(Dispatchers.IO) {
        try {
            val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
            val set = mutableSetOf<Long>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'",
                arrayOf(like),
                null
            )?.use { c ->
                while (c.moveToNext()) set.add(c.getLong(0))
            }
            set
        } catch (_: Exception) { emptySet() }
    }

    override suspend fun getAllMessages(): List<Message> = withContext(Dispatchers.IO) {
        try {
            val list = mutableListOf<Message>()
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
                Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(TelephonyMapper.mapCursorToMessage(cursor))
                }
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "getAllMessages failed", e)
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun getContacts(limit: Int, query: String?): List<com.nospam.nospam.core.model.ContactEntry> = withContext(Dispatchers.IO) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                android.provider.ContactsContract.CommonDataKinds.Phone.STARRED,
                android.provider.ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                android.provider.ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val selection: String?
            val args: Array<String>?
            if (query.isNullOrBlank()) {
                selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} NOT NULL AND ${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} NOT NULL"
                args = null
            } else {
                val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
                selection = "(${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR ${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\') AND ${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} NOT NULL"
                args = arrayOf(like, like)
            }
            val sort = "${android.provider.ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${android.provider.ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC, ${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $limit"
            val list = mutableListOf<com.nospam.nospam.core.model.ContactEntry>()
            val seen = mutableSetOf<String>() // dedup by phone
            context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, args, sort
            )?.use { c ->
                while (c.moveToNext()) {
                    val phone = c.getString(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: continue
                    if (phone.isBlank()) continue
                    val normalized = PhoneNumberNormalizer.normalize(context, phone)
                    // dedup: same normalized number
                    if (!seen.add(normalized)) continue
                    val name = c.getString(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: phone
                    val photo = c.getString(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.PHOTO_URI))
                    val starred = c.getInt(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.STARRED)) == 1
                    val times = c.getInt(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED))
                    val type = c.getInt(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE))
                    val label = c.getString(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.LABEL))
                    val typeLabel = when (type) {
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
                        else -> label ?: "Mobile"
                    }
                    list.add(com.nospam.nospam.core.model.ContactEntry(
                        contactId = c.getLong(c.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)),
                        displayName = name,
                        phone = phone,
                        normalizedPhone = normalized,
                        label = typeLabel,
                        photoUri = photo,
                        starred = starred,
                        timesContacted = times
                    ))
                    if (list.size >= limit) break
                }
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "getContacts failed", e)
            emptyList()
        }
    }
}

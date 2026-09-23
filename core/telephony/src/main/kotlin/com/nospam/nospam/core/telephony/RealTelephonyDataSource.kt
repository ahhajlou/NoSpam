// SPDX-License-Identifier: GPL-3.0-or-later

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
import com.nospam.nospam.core.model.MessageType
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
        /** A contact's display photo is well under this; anything larger is not one. */
        private const val MAX_CONTACT_PHOTO_BYTES = 2 * 1024 * 1024
    }

    private val contactLookup by lazy { ContactLookup(context) }
    private val conversationCacheMutex = Mutex()
    private var cachedConversations: List<Conversation>? = null
    private var cachedMetas: List<ThreadMeta>? = null
    private var cachedLatestMap: Map<Long, SmsLatest> = emptyMap()

    // ThreadMeta and SmsLatest are shared for cache comparison
    internal data class ThreadMeta(val id: Long, val date: Long, val count: Int, val snippet: String, val read: Boolean)
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

    /**
     * Threads that are new since [cached] or whose date, count, read state or
     * snippet differ. Removed threads are not in it: they have nothing to
     * re-read, and [inboxChanged] catches them.
     */
    internal fun changedThreadIds(cached: List<ThreadMeta>?, current: List<ThreadMeta>): Set<Long> {
        val cachedById = cached?.associateBy { it.id } ?: emptyMap()
        return current.filter { meta -> cachedById[meta.id] != meta }.map { it.id }.toSet()
    }

    /**
     * Whether the inbox built from [cached] is out of date for [current]:
     * no cache yet, a thread added or changed, or a thread gone. Checking only
     * the threads still present missed deletions, so a deleted conversation
     * stayed in the inbox until the app restarted.
     */
    internal fun inboxChanged(cached: List<ThreadMeta>?, current: List<ThreadMeta>): Boolean {
        if (cached == null) return true
        if (changedThreadIds(cached, current).isNotEmpty()) return true
        return cached.map { it.id }.toSet() != current.map { it.id }.toSet()
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
            // The plain conversations URI is a UNION view with no message_count column
            // on current Android (API 34+ providers: "no such column: message_count"),
            // which sent every load down the uncached 3000-row fallback. The simple
            // form reads the threads table itself and has all four columns.
            val threadsUri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
            context.contentResolver.query(threadsUri, proj, null, null, "${Telephony.Threads.DATE} DESC")?.use { c ->
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

            // Nothing added, changed or removed: reuse the cached list without
            // touching the Sms table.
            if (!inboxChanged(cached, metas)) {
                snapshot.conversations?.let { return it }
            }
            val changedIds = changedThreadIds(cached, metas)

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
            val countById = metas.associate { it.id to it.count }
            idsToQuery.chunked(400).forEach { chunk ->
                // Rows arrive DATE DESC, so the first row seen for a thread is its
                // newest and the scan can stop once every thread in this chunk has
                // one. Only threads that hold messages can ever be found: the
                // provider lists empty threads too (13 of 282 on the A26), and
                // counting them meant the scan never stopped and read the whole
                // message table on every cold load. A thread holding only MMS also
                // never matches here, so it still costs a full walk.
                var remaining = chunk.count { (countById[it] ?: 0) > 0 }
                if (remaining == 0) return@forEach
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
                            if (--remaining <= 0) return@use
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
        } catch (e: SecurityException) {
            // A revoked READ_SMS lands here. Returning null still falls through to
            // the message-table path, which fails the same way, so the user sees an
            // empty inbox either way — but the cause is no longer invisible.
            Log.w(TAG, "Threads query denied by permissions", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Threads query failed, falling back to message scan", e)
            null
        }
    }

    override suspend fun getMessages(
        threadId: ThreadId,
        limit: Int,
        before: Message?,
    ): List<Message> = withContext(Dispatchers.IO) {
        try {
            queryMessages(threadId, limit, before)
        } catch (e: Exception) {
            // SecurityException (no permission) or SQLiteException (provider
            // column differences) — surface as empty, never crash the UI.
            android.util.Log.w("RealTelephony", "Provider query failed", e)
            emptyList()
        }
    }

    private fun queryMessages(threadId: ThreadId, limit: Int, before: Message?): List<Message> {
        val list = mutableListOf<Message>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        // Backward pagination: newest [limit] rows, or rows strictly older than
        // [before] when scrolling up — never a hard thread truncation. The cursor
        // is (date, _id), the same key as the ORDER BY below.
        val sel = if (before != null) {
            "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
        } else {
            "${Telephony.Sms.THREAD_ID} = ?"
        }
        val args = if (before != null) {
            arrayOf(threadId.value.toString(), before.date.toString(), before.date.toString(), before.id.value.toString())
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

    override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?, messageId: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        val uri = messageId?.let(SmsSender::rowUri)
        try {
            SmsSender.send(context, address, body, subscriptionId, uri)
            Result.success(Unit)
        } catch (e: Exception) {
            if (uri != null) SmsSender.setType(context, uri, MessageType.FAILED)
            Result.failure(e)
        }
    }

    override suspend fun insertOutboxMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? =
        withContext(Dispatchers.IO) {
            try {
                val values = TelephonyMapper.buildOutboxValues(address, body, date, subscriptionId)
                context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
            } catch (e: Exception) {
                // Not the default SMS app: the system stores the message itself.
                Log.w(TAG, "insertOutboxMessage failed", e)
                null
            }
        }

    override suspend fun updateMessageType(messageId: Long, type: MessageType) = withContext(Dispatchers.IO) {
        SmsSender.setType(context, SmsSender.rowUri(messageId), type)
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

    override suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteMessage failed", e)
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

    override suspend fun loadContactPhoto(photoUri: String): ByteArray? = withContext(Dispatchers.IO) {
        val uri = runCatching { android.net.Uri.parse(photoUri) }.getOrNull()
        // Only the contacts provider's own photos: a URI arriving here from
        // anywhere else is not something to open.
        if (uri == null || uri.scheme != "content" ||
            uri.authority != android.provider.ContactsContract.AUTHORITY
        ) return@withContext null
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // Bounded by hand: InputStream.readNBytes is API 33+.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_CONTACT_PHOTO_BYTES) return@use null
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact photo load failed", e)
            null
        }
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

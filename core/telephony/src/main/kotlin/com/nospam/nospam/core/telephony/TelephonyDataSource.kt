// SPDX-License-Identifier: GPL-3.0-or-later

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
    /**
     * Subset of a thread's messages in ascending date/id order.
     * With [before] == null returns the newest [limit] messages; with a
     * [before] message returns up to [limit] messages strictly older than it,
     * in the same (date, id) order they are sorted by. The cursor must be the
     * sort key: paging by row id alone strands messages that are older but were
     * inserted later (imported or restored history).
     * Backward pagination: the thread opens with the newest page and the UI
     * prepends older pages as the user scrolls up, so long threads are never
     * truncated and never loaded in one query.
     */
    suspend fun getMessages(threadId: ThreadId, limit: Int = MESSAGES_PAGE_SIZE, before: Message? = null): List<Message>
    suspend fun sendMessage(address: String, body: String, subscriptionId: Int? = null, messageId: Long? = null): Result<Unit>
    /** Writes an outgoing message as OUTBOX (sending) before it is sent. Null when this app may not write the provider. */
    suspend fun insertOutboxMessage(address: String, body: String, date: Long, subscriptionId: Int? = null): Long?
    suspend fun updateMessageType(messageId: Long, type: com.nospam.nospam.core.model.MessageType)
    suspend fun markAsRead(threadId: ThreadId)
    suspend fun markAsUnread(threadId: ThreadId)
    /**
     * Marks every message of every thread in [threadIds] read or unread, in one
     * provider write per few hundred threads rather than one per thread.
     * Best-effort, like the single-thread forms.
     */
    suspend fun setThreadsRead(threadIds: Collection<ThreadId>, read: Boolean)
    suspend fun deleteConversation(threadId: ThreadId)
    /**
     * Deletes every message of every thread in [threadIds], in one provider
     * delete per few hundred threads rather than one per thread. Best-effort.
     */
    suspend fun deleteConversations(threadIds: Collection<ThreadId>)
    /** Deletes a single message row. Best-effort: a no-op if it no longer exists. */
    suspend fun deleteMessage(messageId: Long)
    /** Inserts an incoming message into the system inbox. Returns the row id, or null on failure. */
    suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean, subscriptionId: Int? = null): Long?
    /** Persists an outgoing message to the sent box (SmsManager never writes it). Null on failure. */
    suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int? = null): Long?
    /** Resolves the thread id for an address, or -1 on failure. */
    suspend fun getOrCreateThreadId(address: String): Long
    suspend fun updateMessageRead(messageId: Long, read: Boolean)
    suspend fun isSystemBlocked(address: String): Boolean
    /**
     * Every number in Android's own block list (blocks made from the dialer or
     * another app as well as ours), as stored. Empty when this app may not read
     * it, which is whenever it is not the default SMS app.
     */
    suspend fun getSystemBlockedNumbers(): List<String>
    suspend fun lookupContact(address: String): com.nospam.nospam.core.model.Participant?
    /**
     * The encoded image behind a contact's photo URI (as found in
     * [com.nospam.nospam.core.model.Participant.photoUri]), or null when there is
     * none, it cannot be read, or the URI is not a contacts-provider URI.
     */
    suspend fun loadContactPhoto(photoUri: String): ByteArray?
    suspend fun hasOutboundMessages(threadId: com.nospam.nospam.core.model.ThreadId): Boolean
    /** All sender addresses this app has sent to — batch protectFromSpam signal for history scans. */
    suspend fun getOutboundSenderAddresses(): Set<String>
    suspend fun getActiveSubscriptions(): List<SimInfo>
    /** The SIM the system uses for SMS by default, or null when there is none (single SIM, or "ask every time"). */
    suspend fun getDefaultSmsSubscriptionId(): Int?
    suspend fun searchBodyMatch(query: String): Set<Long>
    suspend fun getContacts(limit: Int = 50, query: String? = null): List<com.nospam.nospam.core.model.ContactEntry>
    suspend fun getAllMessages(): List<com.nospam.nospam.core.model.Message>
    data class SimInfo(val subscriptionId: Int, val displayName: String, val number: String? = null)

    companion object {
        /** Thread page size for backward pagination (see [getMessages]). */
        const val MESSAGES_PAGE_SIZE = 200
    }
}

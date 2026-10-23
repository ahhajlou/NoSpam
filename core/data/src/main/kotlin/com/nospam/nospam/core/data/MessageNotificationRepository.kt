// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.util.Log
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.preferences.PreferencesDataSource
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** What a conversation's notification shows: the newest [messages], oldest first, of [unreadCount]. */
data class NotificationMessages(val messages: List<Message>, val unreadCount: Int)

/**
 * The messages a conversation's notification lists, as Google Messages, AOSP
 * Messaging and QKSMS choose them: received, unread, and newer than the last
 * time the user swiped that notification away or replied from it. Rebuilt
 * from the provider each time, so it survives the process dying between
 * messages and empties once the conversation is read.
 *
 * The seen times live in their own preferences file. Storage errors never
 * reach the caller: a failed read lists every unread message, a failed write
 * is logged and dropped.
 */
class MessageNotificationRepository(
    private val telephony: TelephonyDataSource,
    private val spam: SpamRepository,
    private val prefs: PreferencesDataSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * The unread messages of [threadId] to show, at most [MAX_MESSAGES] (Google
     * Messages shows 7 and counts the rest). Messages flagged spam are left out,
     * since they were kept silent, unless [includeFlagged]: a saved contact's
     * messages are never classified, so an old verdict says nothing.
     */
    suspend fun unread(threadId: ThreadId, includeFlagged: Boolean): NotificationMessages {
        val seenAt = seenAt(threadId)
        val flagged = if (includeFlagged) emptySet() else spam.observeThreadSpamMessageIds(threadId.value).first()
        val unread = telephony.getMessages(threadId).filter {
            it.type == MessageType.INBOX && !it.read && it.date > seenAt && it.id.value !in flagged
        }
        return NotificationMessages(unread.takeLast(MAX_MESSAGES), unread.size)
    }

    /** The user has seen [threadId]'s notification: what it listed is not shown again. */
    suspend fun markSeen(threadId: ThreadId, at: Long = now()) {
        try {
            prefs.edit(PreferenceFile.NOTIFICATIONS) { it[seenKey(threadId)] = at }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Seen time write failed", e) }
        }
    }

    private suspend fun seenAt(threadId: ThreadId): Long =
        try {
            prefs.data(PreferenceFile.NOTIFICATIONS).first()[seenKey(threadId)] as? Long ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }

    private fun seenKey(threadId: ThreadId) = "seen_${threadId.value}"

    companion object {
        const val MAX_MESSAGES = 7
        private const val TAG = "MessageNotifications"
    }
}

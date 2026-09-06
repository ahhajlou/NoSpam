package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ConversationFilter
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ConversationsRepository(
    private val telephony: TelephonyDataSource,
    private val db: NoSpamDatabase
) {
    private fun withFlags(
        conversations: List<Conversation>,
        spamIds: Set<Long>,
        blockedAddresses: Set<String>,
        archivedIds: Set<Long>,
    ): List<Conversation> = conversations.map { conv ->
        conv.copy(
            isSpam = conv.threadId.value in spamIds,
            isBlocked = conv.participants.any { it.address in blockedAddresses },
            isArchived = conv.threadId.value in archivedIds,
        )
    }

    private fun applyFilter(conversations: List<Conversation>, filter: ConversationFilter): List<Conversation> =
        conversations.filter { conv ->
            when (filter) {
                ConversationFilter.ALL -> !conv.isSpam && !conv.isBlocked && !conv.isArchived
                ConversationFilter.UNREAD -> !conv.read && !conv.isSpam
                ConversationFilter.STARRED -> conv.isStarred
                ConversationFilter.KNOWN -> conv.participants.any { it.displayName != null }
                ConversationFilter.UNKNOWN -> conv.participants.all { it.displayName == null }
            }
        }

    fun observeConversations(filter: ConversationFilter = ConversationFilter.ALL): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.spamVerdictDao.observeSpam(),
            db.blocklistDao.observeAll(),
            db.archivedDao.observeAll(),
        ) { conversations, spamVerdicts, blocklist, archived ->
            applyFilter(
                withFlags(
                    conversations,
                    spamVerdicts.map { it.threadId }.toSet(),
                    blocklist.map { it.address }.toSet(),
                    archived.map { it.threadId }.toSet(),
                ),
                filter,
            )
        }
    }

    fun observeSpam(): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.spamVerdictDao.observeSpam(),
            db.blocklistDao.observeAll(),
        ) { conversations, spamVerdicts, blocklist ->
            val spamIds = spamVerdicts.map { it.threadId }.toSet()
            val blockedAddresses = blocklist.map { it.address }.toSet()
            conversations
                .filter { it.threadId.value in spamIds }
                .map { conv ->
                    conv.copy(isBlocked = conv.participants.any { it.address in blockedAddresses })
                }
        }
    }

    fun observeArchived(): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.archivedDao.observeAll()
        ) { conversations, archived ->
            val archivedIds = archived.map { it.threadId }.toSet()
            conversations
                .filter { it.threadId.value in archivedIds }
                .map { it.copy(isArchived = true) }
        }
    }

    suspend fun setRead(threadId: ThreadId, read: Boolean) {
        if (read) telephony.markAsRead(threadId) else telephony.markAsUnread(threadId)
    }

    suspend fun archive(threadId: ThreadId) = db.archivedDao.archive(threadId.value)

    suspend fun unarchive(threadId: ThreadId) = db.archivedDao.unarchive(threadId.value)

    /**
     * Deletes the provider thread and drops app-owned rows (verdicts, archive
     * flags) so a re-created thread doesn't inherit stale state.
     */
    suspend fun deleteConversation(threadId: ThreadId) {
        telephony.deleteConversation(threadId)
        db.spamVerdictDao.deleteByThread(threadId.value)
        db.archivedDao.unarchive(threadId.value)
    }
}

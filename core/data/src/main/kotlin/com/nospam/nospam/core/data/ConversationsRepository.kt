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
    private fun normalizeAddr(raw: String) = raw.trim().uppercase()

    private fun senderStateFor(conv: Conversation, states: Map<String, com.nospam.nospam.core.database.entity.SenderStateEntity>): com.nospam.nospam.core.database.entity.SenderStateEntity? {
        val addr = conv.participants.firstOrNull()?.address ?: return null
        return states[normalizeAddr(addr)] ?: states[addr]
    }

    private fun withFlags(
        conversations: List<Conversation>,
        senderStates: Map<String, com.nospam.nospam.core.database.entity.SenderStateEntity>,
        spamIds: Set<Long>,
        blockedAddresses: Set<String>,
        archivedIds: Set<Long>,
    ): List<Conversation> = conversations.map { conv ->
        val st = senderStateFor(conv, senderStates)
        val isSpamByState = st?.state == com.nospam.nospam.core.model.ThreadSpamState.SPAM || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED
        // Fallback to legacy SpamVerdict if no sender state yet
        val isSpam = isSpamByState || conv.threadId.value in spamIds
        conv.copy(
            isSpam = isSpam,
            isBlocked = conv.participants.any { it.address in blockedAddresses } || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED,
            isArchived = conv.threadId.value in archivedIds,
        )
    }

    private fun applyFilter(conversations: List<Conversation>, senderStates: Map<String, com.nospam.nospam.core.database.entity.SenderStateEntity>, filter: ConversationFilter): List<Conversation> =
        conversations.filter { conv ->
            when (filter) {
                ConversationFilter.ALL -> {
                    val st = senderStateFor(conv, senderStates)
                    val spam = st?.state == com.nospam.nospam.core.model.ThreadSpamState.SPAM || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED
                    !spam && !conv.isBlocked && !conv.isArchived
                }
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
            db.senderStateDao.observeAll(),
        ) { conversations, spamVerdicts, blocklist, archived, senderStates ->
            val stateMap = senderStates.associateBy { normalizeAddr(it.normalizedAddress) }
            applyFilter(
                withFlags(
                    conversations,
                    stateMap,
                    spamVerdicts.map { it.threadId }.toSet(),
                    blocklist.map { it.address }.toSet(),
                    archived.map { it.threadId }.toSet(),
                ),
                stateMap,
                filter,
            )
        }
    }

    fun observeSpam(): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.spamVerdictDao.observeSpam(),
            db.blocklistDao.observeAll(),
            db.senderStateDao.observeAll(),
        ) { conversations, spamVerdicts, blocklist, senderStates ->
            val spamIds = spamVerdicts.map { it.threadId }.toSet()
            val blockedAddresses = blocklist.map { it.address }.toSet()
            val stateMap = senderStates.associateBy { normalizeAddr(it.normalizedAddress) }
            conversations
                .filter { conv ->
                    val st = senderStateFor(conv, stateMap)
                    st?.state == com.nospam.nospam.core.model.ThreadSpamState.SPAM || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED || conv.threadId.value in spamIds
                }
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
        db.messageVerdictDao.deleteByThread(threadId.value)
        db.archivedDao.unarchive(threadId.value)
    }
}

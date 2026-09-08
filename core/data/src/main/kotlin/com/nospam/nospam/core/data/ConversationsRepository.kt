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
        starredIds: Set<Long>,
        pinnedIds: Set<Long>,
        mutedIds: Set<Long>,
    ): List<Conversation> = conversations.map { conv ->
        val st = senderStateFor(conv, senderStates)
        val isSpamByState = st?.state == com.nospam.nospam.core.model.ThreadSpamState.SPAM || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED
        // Fallback to legacy SpamVerdict if no sender state yet
        val isSpam = isSpamByState || conv.threadId.value in spamIds
        conv.copy(
            isSpam = isSpam,
            isBlocked = conv.participants.any { it.address in blockedAddresses } || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED,
            isArchived = conv.threadId.value in archivedIds,
            spamState = st?.state,
            isStarred = conv.threadId.value in starredIds,
            isPinned = conv.threadId.value in pinnedIds,
            isMuted = conv.threadId.value in mutedIds,
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

    @Suppress("UNCHECKED_CAST")
    fun observeConversations(filter: ConversationFilter = ConversationFilter.ALL): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.spamVerdictDao.observeSpam(),
            db.blocklistDao.observeAll(),
            db.archivedDao.observeAll(),
            db.senderStateDao.observeAll(),
            db.starredDao.observeAll(),
            db.pinnedDao.observeAll(),
            db.mutedDao.observeAll(),
        ) { args ->
            val conversations = args[0] as List<Conversation>
            val spamVerdicts = args[1] as List<com.nospam.nospam.core.database.entity.SpamVerdictEntity>
            val blocklist = args[2] as List<com.nospam.nospam.core.database.entity.BlocklistEntity>
            val archived = args[3] as List<com.nospam.nospam.core.database.entity.ArchivedThreadEntity>
            val senderStates = args[4] as List<com.nospam.nospam.core.database.entity.SenderStateEntity>
            val starred = args[5] as List<com.nospam.nospam.core.database.entity.StarredThreadEntity>
            val pinned = args[6] as List<com.nospam.nospam.core.database.entity.PinnedThreadEntity>
            val muted = args[7] as List<com.nospam.nospam.core.database.entity.MutedThreadEntity>
            val stateMap = senderStates.associateBy { normalizeAddr(it.normalizedAddress) }
            val withFlags = withFlags(
                conversations,
                stateMap,
                spamVerdicts.map { it.threadId }.toSet(),
                blocklist.map { it.address }.toSet(),
                archived.map { it.threadId }.toSet(),
                starred.map { it.threadId }.toSet(),
                pinned.map { it.threadId }.toSet(),
                muted.map { it.threadId }.toSet(),
            )
            // Pinned first
            val sorted = withFlags.sortedWith(compareByDescending<Conversation>{ it.isPinned }.thenByDescending{ it.date })
            applyFilter(sorted, stateMap, filter)
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

    suspend fun toggleStar(threadId: ThreadId) {
        if (db.starredDao.isStarred(threadId.value)) db.starredDao.unstar(threadId.value) else db.starredDao.star(threadId.value)
    }
    suspend fun togglePin(threadId: ThreadId) {
        if (db.pinnedDao.isPinned(threadId.value)) db.pinnedDao.unpin(threadId.value) else db.pinnedDao.pin(threadId.value)
    }
    suspend fun toggleMute(threadId: ThreadId) {
        if (db.mutedDao.isMuted(threadId.value)) db.mutedDao.unmute(threadId.value) else db.mutedDao.mute(threadId.value)
    }
    suspend fun setStar(threadId: ThreadId, starred: Boolean) { if (starred) db.starredDao.star(threadId.value) else db.starredDao.unstar(threadId.value) }
    suspend fun setPin(threadId: ThreadId, pinned: Boolean) { if (pinned) db.pinnedDao.pin(threadId.value) else db.pinnedDao.unpin(threadId.value) }
    suspend fun setMute(threadId: ThreadId, muted: Boolean) { if (muted) db.mutedDao.mute(threadId.value) else db.mutedDao.unmute(threadId.value) }

    suspend fun searchBodyMatch(query: String): Set<Long> = telephony.searchBodyMatch(query)

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

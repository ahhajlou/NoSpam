package com.example.nospam.core.data

import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.ConversationFilter
import com.example.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ConversationsRepository(
    private val telephony: TelephonyDataSource,
    private val db: NoSpamDatabase
) {
    fun observeConversations(filter: ConversationFilter = ConversationFilter.ALL): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.spamVerdictDao.observeSpam(),
            db.blocklistDao.observeAll()
        ) { conversations, spamVerdicts, blocklist ->
            val spamIds = spamVerdicts.map { it.threadId }.toSet()
            val blockedAddresses = blocklist.map { it.address }.toSet()
            conversations.map { conv ->
                val isSpam = conv.threadId.value in spamIds
                val isBlocked = conv.participants.any { it.address in blockedAddresses }
                conv.copy(isSpam = isSpam, isBlocked = isBlocked)
            }.filter { conv ->
                when (filter) {
                    ConversationFilter.ALL -> !conv.isSpam && !conv.isBlocked && !conv.isArchived
                    ConversationFilter.UNREAD -> !conv.read && !conv.isSpam
                    ConversationFilter.STARRED -> conv.isStarred
                    ConversationFilter.KNOWN -> conv.participants.any { it.displayName != null }
                    ConversationFilter.UNKNOWN -> conv.participants.all { it.displayName == null }
                }
            }
        }
    }

    fun observeSpam(): Flow<List<Conversation>> {
        return combine(
            telephony.observeConversations(),
            db.spamVerdictDao.observeSpam()
        ) { conversations, spamVerdicts ->
            val spamIds = spamVerdicts.map { it.threadId }.toSet()
            conversations.filter { it.threadId.value in spamIds }
        }
    }

    fun observeArchived(): Flow<List<Conversation>> {
        return telephony.observeConversations().map { list -> list.filter { it.isArchived } }
    }
}

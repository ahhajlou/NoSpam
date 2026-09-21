// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ConversationFilter
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn

class ConversationsRepository(
    private val telephony: TelephonyDataSource,
    private val db: NoSpamDatabase,
    // Injected for tests – production uses IO, tests can pass TestScope.
    private val externalScope: CoroutineScope? = null,
    // Normalizes a raw provider address to the key used by sender_state/blocklist.
    // Default is a harmless identity-ish fallback; AppContainer injects E.164
    // normalization so lookups match what SmsIngressUseCase stored.
    private val normalizer: (String) -> String = { it.trim().uppercase() },
) {
    // Shared repository scope keeps hot flows alive across ViewModel recreation
    // (e.g. navigating Inbox -> Settings -> Inbox). Without this, each new
    // ViewModel collector triggered a fresh telephony query (3.6s on SM-A730F).
    private val repositoryScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Context the flag combine runs on. Production gets IO; a test that injects
    // its own scope gets that scope's dispatcher, so flowOn does not break the
    // synchronous dispatch those tests rely on.
    private val computeContext: kotlin.coroutines.CoroutineContext =
        externalScope?.coroutineContext?.get(kotlin.coroutines.ContinuationInterceptor)
            ?: Dispatchers.IO

    // Heavy telephony + adjustMixedSnippet shared with replay=1 so revisiting
    // the inbox replays the last list instantly instead of re-querying.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sharedTelephony = telephony.observeConversations()
        .distinctUntilChanged()
        .mapLatest { adjustMixedSnippet(it) }
        .shareIn(repositoryScope, SharingStarted.Eagerly, replay = 1)

    // Flags combine also shared – avoids recombining 6 DB flows on each collector.
    // NOTE: legacy spam_verdict (threadId-keyed) is intentionally NOT part of these
    // list queries — spam membership comes from sender_state (normalized-address-keyed)
    // only, so inbox and spam section can never disagree (§15 / v2 state model).
    @Suppress("UNCHECKED_CAST")
    private val sharedFlags = combine(
        db.blocklistDao.observeAll(),
        db.archivedDao.observeAll(),
        db.senderStateDao.observeAll(),
        db.starredDao.observeAll(),
        db.pinnedDao.observeAll(),
        db.mutedDao.observeAll(),
    ) { args ->
        val blocklist = args[0] as List<com.nospam.nospam.core.database.entity.BlocklistEntity>
        val archived = args[1] as List<com.nospam.nospam.core.database.entity.ArchivedThreadEntity>
        val senderStates = args[2] as List<com.nospam.nospam.core.database.entity.SenderStateEntity>
        val starred = args[3] as List<com.nospam.nospam.core.database.entity.StarredThreadEntity>
        val pinned = args[4] as List<com.nospam.nospam.core.database.entity.PinnedThreadEntity>
        val muted = args[5] as List<com.nospam.nospam.core.database.entity.MutedThreadEntity>
        Flags(
            blockedAddresses = blocklist.map { it.address }.toSet(),
            archivedIds = archived.map { it.threadId }.toSet(),
            senderStates = senderStates.associateBy { normalizeAddr(it.normalizedAddress) },
            starredIds = starred.map { it.threadId }.toSet(),
            pinnedIds = pinned.map { it.threadId }.toSet(),
            mutedIds = muted.map { it.threadId }.toSet(),
        )
    }.shareIn(repositoryScope, SharingStarted.Eagerly, replay = 1)

    private fun normalizeAddr(raw: String) = raw.trim().uppercase()

    private fun senderStateFor(conv: Conversation, states: Map<String, com.nospam.nospam.core.database.entity.SenderStateEntity>): com.nospam.nospam.core.database.entity.SenderStateEntity? {
        val addr = conv.participants.firstOrNull()?.address ?: return null
        // Look up under the normalized form (matches how ingress stored the key),
        // falling back to the exact raw address.
        return states[normalizeAddr(normalizer(addr))] ?: states[addr]
    }

    private fun isBlockedAddress(conv: Conversation, blockedRaw: Set<String>): Boolean {
        if (blockedRaw.isEmpty()) return false
        val blockedNorm = blockedRaw.map { normalizeAddr(normalizer(it)) }.toSet()
        return conv.participants.any {
            val p = it.address.trim()
            p in blockedRaw || normalizeAddr(normalizer(p)) in blockedNorm
        }
    }

    private fun withFlags(
        conversations: List<Conversation>,
        senderStates: Map<String, com.nospam.nospam.core.database.entity.SenderStateEntity>,
        blockedAddresses: Set<String>,
        archivedIds: Set<Long>,
        starredIds: Set<Long>,
        pinnedIds: Set<Long>,
        mutedIds: Set<Long>,
    ): List<Conversation> = conversations.map { conv ->
        val st = senderStateFor(conv, senderStates)
        val isSpam = st?.state == com.nospam.nospam.core.model.ThreadSpamState.SPAM || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED
        conv.copy(
            isSpam = isSpam,
            isBlocked = isBlockedAddress(conv, blockedAddresses) || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED,
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

    fun observeConversations(filter: ConversationFilter = ConversationFilter.ALL): Flow<List<Conversation>> {
        // Cold per-collector – cheap filter/sort, but reuses hot sharedTelephony/flags.
        return combine(sharedTelephony, sharedFlags) { conversations, flags ->
            val withFlags = withFlags(
                conversations,
                flags.senderStates,
                flags.blockedAddresses,
                flags.archivedIds,
                flags.starredIds,
                flags.pinnedIds,
                flags.mutedIds,
            )
            val sorted = withFlags.sortedWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.date })
            applyFilter(sorted, flags.senderStates, filter)
        // Without this the combine runs on the collector's context, which is
        // viewModelScope (Main). withFlags/applyFilter normalize every address,
        // so a trace showed 1332 binder calls to com.android.phone on the main
        // thread per inbox load. observeSpam/observeArchived already hop to IO.
        }.flowOn(computeContext)
    }

    private data class Flags(
        val blockedAddresses: Set<String>,
        val archivedIds: Set<Long>,
        val senderStates: Map<String, com.nospam.nospam.core.database.entity.SenderStateEntity>,
        val starredIds: Set<Long>,
        val pinnedIds: Set<Long>,
        val mutedIds: Set<Long>,
    )

    private suspend fun adjustMixedSnippet(conversations: List<Conversation>): List<Conversation> {
        // Only for MIXED — ensure inbox shows latest ham, not spam promo (now vs Dec 3 bug)
        return conversations.map { conv ->
            if (conv.spamState != com.nospam.nospam.core.model.ThreadSpamState.MIXED) return@map conv
            val verdicts = runCatching { db.messageVerdictDao.getByThread(conv.threadId.value) }.getOrNull() ?: return@map conv
            val hamVerdict = verdicts.filter { !it.isSpam }.maxByOrNull { it.createdAt } ?: return@map conv
            val hamMsg = runCatching { telephony.getMessages(conv.threadId).find { it.id.value == hamVerdict.messageId } }.getOrNull()
            if (hamMsg != null) conv.copy(snippet = hamMsg.body, date = hamMsg.date)
            else conv.copy(date = hamVerdict.createdAt)
        }
    }

    // Spam/Archived also share the hot telephony upstream so navigating
    // to those tabs doesn't re-trigger the 3.6s query.
    // Spam and Archived list newest message first, sorted here rather than trusting
    // the telephony order: adjustMixedSnippet can change a conversation's date after
    // that order was fixed, and the threads table's own date is truncated to seconds.
    // No pinned-first: Google Messages does not apply pins on these pages either.
    // Single source of truth: sender_state (SPAM/BLOCKED) + app blocklist.
    // Legacy spam_verdict (threadId-keyed) is deliberately not consulted here —
    // including it was what let a conversation appear in both lists (see CLAUDE.md §15).
    fun observeSpam(): Flow<List<Conversation>> {
        return combine(
            sharedTelephony,
            db.blocklistDao.observeAll(),
            db.senderStateDao.observeAll(),
        ) { conversations, blocklist, senderStates ->
            val blockedRaw = blocklist.map { it.address }.toSet()
            val stateMap = senderStates.associateBy { normalizeAddr(it.normalizedAddress) }
            conversations
                .filter { conv ->
                    val st = senderStateFor(conv, stateMap)
                    st?.state == com.nospam.nospam.core.model.ThreadSpamState.SPAM || st?.state == com.nospam.nospam.core.model.ThreadSpamState.BLOCKED || isBlockedAddress(conv, blockedRaw)
                }
                .map { conv ->
                    conv.copy(isBlocked = isBlockedAddress(conv, blockedRaw))
                }
                .sortedByDescending { it.date }
        }.flowOn(Dispatchers.IO)
    }

    fun observeArchived(): Flow<List<Conversation>> {
        return combine(
            sharedTelephony,
            db.archivedDao.observeAll()
        ) { conversations, archived ->
            val archivedIds = archived.map { it.threadId }.toSet()
            conversations
                .filter { it.threadId.value in archivedIds }
                .map { it.copy(isArchived = true) }
                .sortedByDescending { it.date }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun setRead(threadId: ThreadId, read: Boolean) {
        if (read) telephony.markAsRead(threadId) else telephony.markAsUnread(threadId)
    }

    /**
     * Archiving also drops the pin, as Google Messages does (checked on the
     * emulator: an archived conversation comes back from Archived unpinned).
     * A pin on a conversation the user has put away would otherwise reappear
     * the day they unarchive it.
     */
    suspend fun archive(threadId: ThreadId) {
        db.pinnedDao.unpin(threadId.value)
        db.archivedDao.archive(threadId.value)
    }

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

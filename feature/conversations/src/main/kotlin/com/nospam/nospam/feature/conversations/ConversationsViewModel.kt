// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.BackfillStatus
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ConversationFilter
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val pinned: List<Conversation> = emptyList(),
    val filter: ConversationFilter = ConversationFilter.ALL,
    val searchQuery: String = "",
    val isSearchFocused: Boolean = false,
    val isLoading: Boolean = true,
    /** Non-null while a one-time history scan is running or just finished. */
    val backfillProgress: BackfillStatus? = null,
)

/**
 * @param repository when null (previews, unit tests), serves the fake list.
 * When provided (NavHost via AppContainer), serves the real provider query
 * through [ConversationsRepository], filtered client-side by chip + search.
 */
class ConversationsViewModel(
    private val repository: ConversationsRepository? = null,
    /** Live scan status when a container provides one; null for tests/previews. */
    private val backfillStatus: StateFlow<BackfillStatus?>? = null,
    private val onCancelBackfill: () -> Unit = {},
) : ViewModel() {
    private val _filter = MutableStateFlow(ConversationFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchFocused = MutableStateFlow(false)
    private val _isDefaultSmsApp = MutableStateFlow(true)
    val isDefaultSmsApp: StateFlow<Boolean> = _isDefaultSmsApp.asStateFlow()

    fun checkDefaultSmsApp(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = isDefaultSmsAppSync(context.applicationContext)
            _isDefaultSmsApp.value = result
        }
    }

    private fun isDefaultSmsAppSync(context: Context): Boolean =
        com.nospam.nospam.core.telephony.DefaultSmsApp.isHeld(context)

    // Fake fallback: plain StateFlow, no Main dispatcher needed (tests/previews).
    private val all = fakeConversations()
    private val _fakeState = MutableStateFlow(
        ConversationsUiState(
            conversations = all.drop(1),
            pinned = all.take(1),
            isLoading = false
        )
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _realState: StateFlow<ConversationsUiState>? = repository?.let { repo ->
        val bodyMatches = _searchQuery.flatMapLatest { q ->
            kotlinx.coroutines.flow.flow {
                emit(if (q.isBlank()) emptySet() else runCatching { repo.searchBodyMatch(q) }.getOrDefault(emptySet()))
            }
        }
        val baseState = combine(
            _filter.flatMapLatest { repo.observeConversations(it) },
            _filter,
            _searchQuery,
            _isSearchFocused,
            bodyMatches,
        ) { conversations, filter, query, focused, bodySet ->
            val searched = if (query.isBlank()) conversations
            else conversations.filter { matchesQuery(it, query, bodySet) }
            ConversationsUiState(
                conversations = searched.filterNot { it.isPinned },
                pinned = searched.filter { it.isPinned },
                filter = filter,
                searchQuery = query,
                isSearchFocused = focused,
                isLoading = false
            )
        }
        combine(
            baseState,
            backfillStatus ?: MutableStateFlow<BackfillStatus?>(null),
        ) { ui, backfill -> ui.copy(backfillProgress = backfill) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())
    }

    val uiState: StateFlow<ConversationsUiState> = _realState ?: _fakeState.asStateFlow()

    /** True when serving live provider data (used for empty-state copy). */
    val isLive: Boolean = _realState != null

    fun onFilterSelected(filter: ConversationFilter) {
        _filter.value = filter
        _fakeState.value = _fakeState.value.copy(filter = filter)
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _fakeState.value = _fakeState.value.copy(searchQuery = query)
    }

    fun onSearchFocusChanged(focused: Boolean) {
        _isSearchFocused.value = focused
        _fakeState.value = _fakeState.value.copy(isSearchFocused = focused)
    }

    fun cancelBackfill() {
        onCancelBackfill()
    }

    /**
     * Explicit setters, not toggles: applied to a selection they must converge.
     * Toggling a half-pinned selection would unpin the rows already pinned.
     */
    fun setStarred(threadIds: Collection<Long>, starred: Boolean) {
        val repo = repository
        if (repo != null) {
            viewModelScope.launch { repo.setStar(threadIds.map(::ThreadId), starred) }
        }
        updateFake(threadIds) { it.copy(isStarred = starred) }
    }

    fun setPinned(threadIds: Collection<Long>, pinned: Boolean) {
        val repo = repository
        if (repo != null) {
            viewModelScope.launch { repo.setPin(threadIds.map(::ThreadId), pinned) }
        }
        updateFake(threadIds) { it.copy(isPinned = pinned) }
    }

    fun setMuted(threadIds: Collection<Long>, muted: Boolean) {
        val repo = repository
        if (repo != null) {
            viewModelScope.launch { repo.setMute(threadIds.map(::ThreadId), muted) }
        }
        updateFake(threadIds) { it.copy(isMuted = muted) }
    }

    /** Previews and tests without a repository: apply the change to the seed list. */
    private fun updateFake(threadIds: Collection<Long>, change: (Conversation) -> Conversation) {
        if (repository != null) return
        _fakeState.value = _fakeState.value.let { s ->
            val updated = (s.pinned + s.conversations).map { if (it.threadId.value in threadIds) change(it) else it }
            s.copy(pinned = updated.filter { it.isPinned }, conversations = updated.filterNot { it.isPinned })
        }
    }

    private fun matchesQuery(conversation: Conversation, query: String, bodySet: Set<Long> = emptySet()): Boolean {
        if (conversation.snippet.contains(query, ignoreCase = true)) return true
        if (conversation.threadId.value in bodySet) return true
        return conversation.participants.any {
            it.address.contains(query, ignoreCase = true) ||
                (it.displayName?.contains(query, ignoreCase = true) == true)
        }
    }

    init {
        // Simple log for inbox load time (easy peasy)
        viewModelScope.launch {
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                val flowToObserve: kotlinx.coroutines.flow.Flow<ConversationsUiState> = _realState ?: _fakeState
                val result = flowToObserve.first { it.conversations.isNotEmpty() || it.pinned.isNotEmpty() }
                val dur = android.os.SystemClock.elapsedRealtime() - start
                android.util.Log.i("NoSpamPerf", "inbox loaded: ${result.conversations.size + result.pinned.size} in ${dur}ms")
            } catch (_: Exception) {}
        }
    }

    private fun fakeConversations(): List<Conversation> = listOf(
        Conversation(ThreadId(1), listOf(Participant("Alice Smith", "Alice Smith")), "Are we still on for lunch tomorrow?", System.currentTimeMillis(), 5, false, isPinned = true),
        Conversation(ThreadId(2), listOf(Participant("Design Team Sync")), "Sam: I uploaded the new Figma file", System.currentTimeMillis() - 86400000, 12, true),
        Conversation(ThreadId(3), listOf(Participant("Delivery Driver")), "Your package has been left at the front door.", System.currentTimeMillis() - 2*86400000, 1, true),
        Conversation(ThreadId(4), listOf(Participant("Mom")), "I'll call you later to discuss the", System.currentTimeMillis() - 3*86400000, 2, true, hasDraft = true),
        Conversation(ThreadId(5), listOf(Participant("Aunt Sarah")), "Look at these old photos I found!", System.currentTimeMillis() - 5*86400000, 1, true),
        Conversation(ThreadId(6), listOf(Participant("Bank Alerts")), "Your verification code is: 849201", System.currentTimeMillis() - 7*86400000, 1, true),
    )
}

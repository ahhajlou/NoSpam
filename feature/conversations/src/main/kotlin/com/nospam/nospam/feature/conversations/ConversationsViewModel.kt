package com.nospam.nospam.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ConversationFilter
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val pinned: List<Conversation> = emptyList(),
    val filter: ConversationFilter = ConversationFilter.ALL,
    val searchQuery: String = "",
    val isSearchFocused: Boolean = false
)

/**
 * @param repository when null (previews, unit tests), serves the fake list.
 * When provided (NavHost via AppContainer), serves the real provider query
 * through [ConversationsRepository], filtered client-side by chip + search.
 */
class ConversationsViewModel(
    private val repository: ConversationsRepository? = null,
) : ViewModel() {
    private val _filter = MutableStateFlow(ConversationFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchFocused = MutableStateFlow(false)

    // Fake fallback: plain StateFlow, no Main dispatcher needed (tests/previews).
    private val all = fakeConversations()
    private val _fakeState = MutableStateFlow(
        ConversationsUiState(
            conversations = all.drop(1),
            pinned = all.take(1)
        )
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _realState: StateFlow<ConversationsUiState>? = repository?.let { repo ->
        val bodyMatches = _searchQuery.flatMapLatest { q ->
            kotlinx.coroutines.flow.flow {
                emit(if (q.isBlank()) emptySet() else runCatching { repo.searchBodyMatch(q) }.getOrDefault(emptySet()))
            }
        }
        combine(
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())
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

    fun toggleStar(threadId: Long) {
        val repo = repository ?: return
        viewModelScope.launch { repo.toggleStar(ThreadId(threadId)) }
        // Optimistic fake update
        _fakeState.value = _fakeState.value.let { s ->
            s.copy(
                conversations = s.conversations.map { if (it.threadId.value == threadId) it.copy(isStarred = !it.isStarred) else it },
                pinned = s.pinned.map { if (it.threadId.value == threadId) it.copy(isStarred = !it.isStarred) else it }
            )
        }
    }
    fun togglePin(threadId: Long) {
        val repo = repository ?: return
        viewModelScope.launch { repo.togglePin(ThreadId(threadId)) }
        _fakeState.value = _fakeState.value.let { s ->
            val all = (s.pinned + s.conversations)
            val updated = all.map { if (it.threadId.value == threadId) it.copy(isPinned = !it.isPinned) else it }
            s.copy(pinned = updated.filter { it.isPinned }, conversations = updated.filterNot { it.isPinned })
        }
    }
    fun toggleMute(threadId: Long) {
        val repo = repository ?: return
        viewModelScope.launch { repo.toggleMute(ThreadId(threadId)) }
    }

    private fun matchesQuery(conversation: Conversation, query: String, bodySet: Set<Long> = emptySet()): Boolean {
        if (conversation.snippet.contains(query, ignoreCase = true)) return true
        if (conversation.threadId.value in bodySet) return true
        return conversation.participants.any {
            it.address.contains(query, ignoreCase = true) ||
                (it.displayName?.contains(query, ignoreCase = true) == true)
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

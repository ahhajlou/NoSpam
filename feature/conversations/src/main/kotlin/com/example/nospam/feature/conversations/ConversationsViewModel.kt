package com.example.nospam.feature.conversations

import androidx.lifecycle.ViewModel
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.ConversationFilter
import com.example.nospam.core.model.Participant
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val pinned: List<Conversation> = emptyList(),
    val filter: ConversationFilter = ConversationFilter.ALL,
    val searchQuery: String = "",
    val isSearchFocused: Boolean = false
)

class ConversationsViewModel : ViewModel() {
    private val all = fakeConversations()
    private val _uiState = MutableStateFlow(ConversationsUiState(
        conversations = all.drop(1),
        pinned = all.take(1)
    ))
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    fun onFilterSelected(filter: ConversationFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun onSearchFocusChanged(focused: Boolean) {
        _uiState.value = _uiState.value.copy(isSearchFocused = focused)
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

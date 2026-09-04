package com.example.nospam.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nospam.core.data.ConversationsRepository
import com.example.nospam.core.model.Conversation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Live spam list from verdicts. Null repository → null flow (caller uses fake seed). */
class SpamViewModel(repository: ConversationsRepository) : ViewModel() {
    val conversations: StateFlow<List<Conversation>> = repository.observeSpam()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Archived threads. The Telephony provider has no archived flag, so this is
 * empty until an app-owned archived-thread store exists (future work) —
 * the screen truthfully shows its empty state.
 */
class ArchivedViewModel(repository: ConversationsRepository) : ViewModel() {
    val conversations: StateFlow<List<Conversation>> = repository.observeArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

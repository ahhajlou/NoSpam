// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.model.Conversation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Spam & blocked conversations. [conversations] is null until the first load
 * arrives, so the screen can tell "still loading" from "nothing here": starting
 * from an empty list made the page claim it was empty while it loaded.
 */
class SpamViewModel(repository: ConversationsRepository) : ViewModel() {
    val conversations: StateFlow<List<Conversation>?> = repository.observeSpam()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Archived conversations: the provider has no archived flag, so these are the
 * threads flagged in `nospam.db`. Null until the first load, as for [SpamViewModel].
 */
class ArchivedViewModel(repository: ConversationsRepository) : ViewModel() {
    val conversations: StateFlow<List<Conversation>?> = repository.observeArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

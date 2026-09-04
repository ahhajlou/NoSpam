package com.example.nospam.feature.conversations

import com.example.nospam.core.model.ConversationFilter
import org.junit.Assert.*
import org.junit.Test

class ConversationsViewModelTest {
    @Test fun `initial state has pinned item excluded from main list`() {
        val vm = ConversationsViewModel()
        val state = vm.uiState.value
        assertEquals(1, state.pinned.size)
        assertEquals(5, state.conversations.size)
        // Regression: pinned ThreadId(1) once appeared in both lists, crashing
        // LazyColumn with duplicate key "1".
        val allIds = (state.pinned + state.conversations).map { it.threadId.value }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test fun `filter selection updates state`() {
        val vm = ConversationsViewModel()
        assertEquals(ConversationFilter.ALL, vm.uiState.value.filter)
        vm.onFilterSelected(ConversationFilter.STARRED)
        assertEquals(ConversationFilter.STARRED, vm.uiState.value.filter)
    }

    @Test fun `search query and focus update state`() {
        val vm = ConversationsViewModel()
        vm.onSearchQueryChanged("alice")
        assertEquals("alice", vm.uiState.value.searchQuery)
        vm.onSearchFocusChanged(true)
        assertTrue(vm.uiState.value.isSearchFocused)
        vm.onSearchFocusChanged(false)
        assertFalse(vm.uiState.value.isSearchFocused)
    }
}

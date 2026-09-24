// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.BlocklistRepository
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One sender rule, with the contact's name and photo when the sender is a contact. */
data class SenderRule(
    val address: String,
    val displayName: String? = null,
    val photoUri: String? = null,
)

/**
 * The user's sender rules. A list is null until it has loaded, so the page
 * never shows "no blocked senders" for a list it has not read yet.
 */
data class SendersUiState(
    val blocked: List<SenderRule>? = null,
    val allowed: List<SenderRule>? = null,
)

/**
 * The "Blocked and allowed senders" page. Rules are keyed by sender and outlive
 * the conversation (TODO.md "Settings"), so this page is the only way back to a
 * rule whose conversation was deleted.
 *
 * All parameters are null in previews and tests that do not need them; a
 * missing repository shows its list as empty.
 */
class SendersViewModel(
    private val blocklist: BlocklistRepository? = null,
    private val spam: SpamRepository? = null,
    private val telephony: TelephonyDataSource? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SendersUiState())
    val uiState: StateFlow<SendersUiState> = _uiState.asStateFlow()

    // Contact lookups, remembered for the life of the page.
    private val contacts = mutableMapOf<String, Participant?>()

    init {
        val blocked = blocklist
        if (blocked == null) {
            _uiState.value = _uiState.value.copy(blocked = emptyList())
        } else viewModelScope.launch {
            blocked.observeBlockedSenders().collect { addresses ->
                val rules = addresses.map { rule(it) }
                _uiState.value = _uiState.value.copy(blocked = rules)
            }
        }
        val allowed = spam
        if (allowed == null) {
            _uiState.value = _uiState.value.copy(allowed = emptyList())
        } else viewModelScope.launch {
            allowed.observeAllowedSenders().collect { addresses ->
                val rules = addresses.map { rule(it) }
                _uiState.value = _uiState.value.copy(allowed = rules)
            }
        }
    }

    /** Removes the block from this app and from Android's own block list. */
    fun unblock(address: String) {
        val repo = blocklist ?: return
        viewModelScope.launch { repo.unblock(address) }
    }

    /** Undoes "Not spam": the sender is filtered automatically again. */
    fun removeAllow(address: String) {
        val repo = spam ?: return
        viewModelScope.launch { repo.removeAllow(address) }
    }

    private suspend fun rule(address: String): SenderRule {
        val contact = if (contacts.containsKey(address)) contacts[address] else lookup(address).also { contacts[address] = it }
        return SenderRule(address, contact?.displayName, contact?.photoUri)
    }

    private suspend fun lookup(address: String): Participant? = try {
        telephony?.lookupContact(address)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

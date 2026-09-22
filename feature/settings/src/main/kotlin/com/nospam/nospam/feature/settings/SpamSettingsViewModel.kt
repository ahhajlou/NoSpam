// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpamSettingsUiState(
    /** Whether incoming messages are classified. On until storage says otherwise. */
    val spamProtection: Boolean = true,
)

/**
 * @param settings null in previews and tests, which then keep the switch in
 * memory only.
 */
class SpamSettingsViewModel(
    private val settings: SettingsRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpamSettingsUiState())
    val uiState: StateFlow<SpamSettingsUiState> = _uiState.asStateFlow()

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                repo.spamProtection.collect { _uiState.value = _uiState.value.copy(spamProtection = it) }
            }
        }
    }

    fun setSpamProtection(enabled: Boolean) {
        val repo = settings
        if (repo == null) {
            _uiState.value = _uiState.value.copy(spamProtection = enabled)
        } else {
            // The switch follows the stored value, so a failed write snaps it back.
            viewModelScope.launch { repo.setSpamProtection(enabled) }
        }
    }
}

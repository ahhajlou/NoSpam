// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.model.SwipeActions
import com.nospam.nospam.core.model.ThemeSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The stored appearance settings. The device-backed rows on the same page
 * (default SMS app, notifications, bubbles, language) are read from the
 * system on resume instead, because the user changes them outside the app.
 */
data class GeneralSettingsUiState(
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
    val dynamicColor: Boolean = false,
    val swipeActions: SwipeActions = SwipeActions(),
    val messageSounds: Boolean = true,
)

/**
 * @param settings null in previews and tests, which then keep the choices in
 * memory only.
 */
class GeneralSettingsViewModel(
    private val settings: SettingsRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GeneralSettingsUiState())
    val uiState: StateFlow<GeneralSettingsUiState> = _uiState.asStateFlow()

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                repo.theme.collect { _uiState.value = _uiState.value.copy(theme = it) }
            }
            viewModelScope.launch {
                repo.dynamicColor.collect { _uiState.value = _uiState.value.copy(dynamicColor = it) }
            }
            viewModelScope.launch {
                repo.swipeActions.collect { _uiState.value = _uiState.value.copy(swipeActions = it) }
            }
            viewModelScope.launch {
                repo.messageSounds.collect { _uiState.value = _uiState.value.copy(messageSounds = it) }
            }
        }
    }

    // With storage, the state follows the stored value, so a failed write
    // leaves the page showing what is actually in effect.
    fun setTheme(theme: ThemeSetting) {
        val repo = settings ?: return run { _uiState.value = _uiState.value.copy(theme = theme) }
        viewModelScope.launch { repo.setTheme(theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        val repo = settings ?: return run { _uiState.value = _uiState.value.copy(dynamicColor = enabled) }
        viewModelScope.launch { repo.setDynamicColor(enabled) }
    }

    fun setSwipeActions(actions: SwipeActions) {
        val repo = settings ?: return run { _uiState.value = _uiState.value.copy(swipeActions = actions) }
        viewModelScope.launch { repo.setSwipeActions(actions) }
    }

    fun setMessageSounds(enabled: Boolean) {
        val repo = settings ?: return run { _uiState.value = _uiState.value.copy(messageSounds = enabled) }
        viewModelScope.launch { repo.setMessageSounds(enabled) }
    }
}

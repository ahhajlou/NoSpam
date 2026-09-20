package com.nospam.nospam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    /** Active SIMs, one settings page each. Empty until loaded, or with no SIM. */
    val sims: List<TelephonyDataSource.SimInfo> = emptyList(),
)

/**
 * @param dataSource null in previews and tests, which then show no SIM pages.
 */
class SettingsViewModel(
    private val dataSource: TelephonyDataSource? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val source = dataSource
        if (source != null) {
            viewModelScope.launch {
                val sims = runCatching { source.getActiveSubscriptions() }.getOrDefault(emptyList())
                _uiState.value = SettingsUiState(sims = sims)
            }
        }
    }

    fun simById(subscriptionId: Int): TelephonyDataSource.SimInfo? =
        _uiState.value.sims.firstOrNull { it.subscriptionId == subscriptionId }
}

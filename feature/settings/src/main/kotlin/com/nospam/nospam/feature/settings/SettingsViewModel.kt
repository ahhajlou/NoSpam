// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    /** Active SIMs, one settings page each. Empty until loaded, or with no SIM. */
    val sims: List<TelephonyDataSource.SimInfo> = emptyList(),
    /** Numbers the user entered, by subscription id; they win over the carrier's. */
    val enteredNumbers: Map<Int, String> = emptyMap(),
    /** SIMs that ask for delivery reports. */
    val deliveryReportSims: Set<Int> = emptySet(),
) {
    /** The number to show for [sim]: the user's, else the carrier's, else null. */
    fun numberOf(sim: TelephonyDataSource.SimInfo): String? = enteredNumbers[sim.subscriptionId] ?: sim.number
}

/**
 * @param dataSource null in previews and tests, which then show no SIM pages.
 * @param settings null in previews and tests, which then keep entered numbers
 * in memory only.
 */
class SettingsViewModel(
    private val dataSource: TelephonyDataSource? = null,
    private val settings: SettingsRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val source = dataSource
        if (source != null) {
            viewModelScope.launch {
                val sims = runCatching { source.getActiveSubscriptions() }.getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(sims = sims)
            }
        }
        settings?.let { repo ->
            viewModelScope.launch {
                repo.simNumbers.collect { _uiState.value = _uiState.value.copy(enteredNumbers = it) }
            }
            viewModelScope.launch {
                repo.deliveryReportSims.collect { _uiState.value = _uiState.value.copy(deliveryReportSims = it) }
            }
        }
    }

    /** Stores the user's number for the SIM; null or blank clears it. */
    fun setSimNumber(subscriptionId: Int, number: String?) {
        val repo = settings
        if (repo == null) {
            val trimmed = number?.trim()
            val numbers = _uiState.value.enteredNumbers.toMutableMap().apply {
                if (trimmed.isNullOrEmpty()) remove(subscriptionId) else put(subscriptionId, trimmed)
            }
            _uiState.value = _uiState.value.copy(enteredNumbers = numbers)
        } else {
            viewModelScope.launch { repo.setSimNumber(subscriptionId, number) }
        }
    }

    fun setDeliveryReports(subscriptionId: Int, enabled: Boolean) {
        val repo = settings
        if (repo == null) {
            val sims = _uiState.value.deliveryReportSims
            _uiState.value = _uiState.value.copy(deliveryReportSims = if (enabled) sims + subscriptionId else sims - subscriptionId)
        } else {
            viewModelScope.launch { repo.setDeliveryReports(subscriptionId, enabled) }
        }
    }
}

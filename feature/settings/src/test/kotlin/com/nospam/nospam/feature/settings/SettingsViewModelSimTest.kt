// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Plain-JUnit tests for SettingsViewModel's SIM number state (`uiState.sims`,
 * `uiState.enteredNumbers`, `SettingsUiState.numberOf`, `setSimNumber`),
 * written from the spec in the task prompt independently of the
 * implementation. Compose-facing behaviour is covered separately in
 * SimNumberRobolectricTest.
 */
class SettingsViewModelSimTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun sim(id: Int, number: String? = null) =
        TelephonyDataSource.SimInfo(subscriptionId = id, displayName = "SIM $id", number = number)

    // --- SettingsUiState.numberOf -----------------------------------------

    @Test fun `numberOf returns the entered number when present, even if a carrier number also exists`() {
        val state = SettingsUiState(enteredNumbers = mapOf(1 to "+15559998888"))
        assertEquals("+15559998888", state.numberOf(sim(1, number = "+15550001111")))
    }

    @Test fun `numberOf falls back to the carrier number when nothing was entered`() {
        val state = SettingsUiState(enteredNumbers = emptyMap())
        assertEquals("+15550001111", state.numberOf(sim(1, number = "+15550001111")))
    }

    @Test fun `numberOf is null when neither an entered nor a carrier number exists`() {
        val state = SettingsUiState(enteredNumbers = emptyMap())
        assertNull(state.numberOf(sim(1, number = null)))
    }

    @Test fun `numberOf keys the lookup by the given SIM's own subscription id`() {
        val state = SettingsUiState(enteredNumbers = mapOf(2 to "+15559998888"))
        assertEquals("+15550001111", state.numberOf(sim(1, number = "+15550001111")))
    }

    // --- No collaborators ---------------------------------------------------

    @Test fun `with nothing given, sims and enteredNumbers start empty`() = runTest {
        val vm = SettingsViewModel()
        assertEquals(emptyList<TelephonyDataSource.SimInfo>(), vm.uiState.value.sims)
        assertEquals(emptyMap<Int, String>(), vm.uiState.value.enteredNumbers)
    }

    // --- TelephonyDataSource only --------------------------------------------

    @Test fun `sims are filled in from the data source`() = runTest {
        val fake = FakeTelephonyDataSource().apply {
            subscriptions = listOf(sim(1, number = "+15550001111"), sim(2, number = "+15559998888"))
        }
        val vm = SettingsViewModel(dataSource = fake)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(sim(1, "+15550001111"), sim(2, "+15559998888")), vm.uiState.value.sims)
    }

    @Test fun `without a repository, setSimNumber only changes state in memory`() = runTest {
        val vm = SettingsViewModel()
        vm.setSimNumber(1, "+15550001111")
        assertEquals(mapOf(1 to "+15550001111"), vm.uiState.value.enteredNumbers)
    }

    @Test fun `without a repository, setSimNumber trims the number`() = runTest {
        val vm = SettingsViewModel()
        vm.setSimNumber(1, "  +1 555 000 1111  ")
        assertEquals(mapOf(1 to "+1 555 000 1111"), vm.uiState.value.enteredNumbers)
    }

    @Test fun `without a repository, setSimNumber with null removes the entry`() = runTest {
        val vm = SettingsViewModel()
        vm.setSimNumber(1, "+15550001111")
        vm.setSimNumber(1, null)
        assertEquals(emptyMap<Int, String>(), vm.uiState.value.enteredNumbers)
    }

    @Test fun `without a repository, setSimNumber with a blank string removes the entry`() = runTest {
        val vm = SettingsViewModel()
        vm.setSimNumber(1, "+15550001111")
        vm.setSimNumber(1, "   ")
        assertEquals(emptyMap<Int, String>(), vm.uiState.value.enteredNumbers)
    }

    @Test fun `without a repository, entries for different SIMs are independent`() = runTest {
        val vm = SettingsViewModel()
        vm.setSimNumber(1, "+15550001111")
        vm.setSimNumber(2, "+15559998888")
        assertEquals(mapOf(1 to "+15550001111", 2 to "+15559998888"), vm.uiState.value.enteredNumbers)
    }

    // --- With a SettingsRepository --------------------------------------------

    @Test fun `with a repository, enteredNumbers reflects a pre-stored value`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSimNumber(1, "+15550001111")
        val vm = SettingsViewModel(settings = repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(mapOf(1 to "+15550001111"), vm.uiState.value.enteredNumbers)
    }

    @Test fun `with a repository, setSimNumber writes through and the state follows`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = SettingsViewModel(settings = repo)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setSimNumber(1, "+15550001111")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(mapOf(1 to "+15550001111"), vm.uiState.value.enteredNumbers)
        assertEquals("+15550001111", fake.contents(PreferenceFile.SIM_SETTINGS)["sim_1_number"])
    }

    @Test fun `with a repository, setSimNumber with null writes through and removes the entry`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSimNumber(1, "+15550001111")
        val vm = SettingsViewModel(settings = repo)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setSimNumber(1, null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyMap<Int, String>(), vm.uiState.value.enteredNumbers)
    }
}

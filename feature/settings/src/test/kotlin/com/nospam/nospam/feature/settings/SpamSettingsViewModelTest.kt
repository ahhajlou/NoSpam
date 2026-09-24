// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for SpamSettingsViewModel written from the spec (default state,
 * write-through behaviour, and failure fallback), not from the
 * implementation.
 */
class SpamSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `with no repository the state starts true`() = runTest {
        val vm = SpamSettingsViewModel()
        assertTrue(vm.uiState.value.spamProtection)
    }

    @Test fun `with no repository setSpamProtection changes state in memory`() = runTest {
        val vm = SpamSettingsViewModel()
        vm.setSpamProtection(false)
        assertFalse(vm.uiState.value.spamProtection)
        vm.setSpamProtection(true)
        assertTrue(vm.uiState.value.spamProtection)
    }

    @Test fun `with a repository the state reflects a pre-stored false`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("spam_protection_enabled" to false)),
        )
        val repo = SettingsRepository(fake)
        val vm = SpamSettingsViewModel(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.spamProtection)
    }

    @Test fun `setSpamProtection writes through to storage and the state follows it`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = SpamSettingsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setSpamProtection(false)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.spamProtection)
        assertEquals(false, fake.contents(PreferenceFile.SETTINGS)["spam_protection_enabled"])
    }

    @Test fun `when the write fails, the state keeps showing the stored value`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = SpamSettingsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.spamProtection)

        fake.failWrites = true
        vm.setSpamProtection(false)
        dispatcher.scheduler.advanceUntilIdle()

        // The write failed, so storage still holds no override: the stored
        // (default) value is true, and the state must not claim false.
        assertTrue(vm.uiState.value.spamProtection)
    }
}

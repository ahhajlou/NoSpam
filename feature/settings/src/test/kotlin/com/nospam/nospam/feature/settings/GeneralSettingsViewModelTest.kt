// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.model.ThemeSetting
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
 * Tests for GeneralSettingsViewModel written from the spec (default state,
 * write-through behaviour, and failure fallback), not from the
 * implementation.
 */
class GeneralSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `with no repository the state starts at defaults`() = runTest {
        val vm = GeneralSettingsViewModel()
        assertEquals(ThemeSetting.SYSTEM, vm.uiState.value.theme)
        assertFalse(vm.uiState.value.dynamicColor)
    }

    @Test fun `with no repository setTheme changes state in memory`() = runTest {
        val vm = GeneralSettingsViewModel()
        vm.setTheme(ThemeSetting.DARK)
        assertEquals(ThemeSetting.DARK, vm.uiState.value.theme)
        vm.setTheme(ThemeSetting.LIGHT)
        assertEquals(ThemeSetting.LIGHT, vm.uiState.value.theme)
    }

    @Test fun `with no repository setDynamicColor changes state in memory`() = runTest {
        val vm = GeneralSettingsViewModel()
        vm.setDynamicColor(true)
        assertTrue(vm.uiState.value.dynamicColor)
        vm.setDynamicColor(false)
        assertFalse(vm.uiState.value.dynamicColor)
    }

    @Test fun `with a repository the state reflects a pre-stored theme and dynamic color`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(
                PreferenceFile.UI_SETTINGS to mapOf("theme" to "DARK", "dynamic_color" to true),
            ),
        )
        val repo = SettingsRepository(fake)
        val vm = GeneralSettingsViewModel(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeSetting.DARK, vm.uiState.value.theme)
        assertTrue(vm.uiState.value.dynamicColor)
    }

    @Test fun `setTheme writes through to storage and the state follows it`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = GeneralSettingsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setTheme(ThemeSetting.DARK)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeSetting.DARK, vm.uiState.value.theme)
        assertEquals("DARK", fake.contents(PreferenceFile.UI_SETTINGS)["theme"])
    }

    @Test fun `setDynamicColor writes through to storage and the state follows it`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = GeneralSettingsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setDynamicColor(true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.dynamicColor)
        assertEquals(true, fake.contents(PreferenceFile.UI_SETTINGS)["dynamic_color"])
    }

    @Test fun `when the theme write fails, the state keeps showing the stored value`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = GeneralSettingsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ThemeSetting.SYSTEM, vm.uiState.value.theme)

        fake.failWrites = true
        vm.setTheme(ThemeSetting.DARK)
        dispatcher.scheduler.advanceUntilIdle()

        // The write failed, so storage still holds no override: the stored
        // (default) value is SYSTEM, and the state must not claim DARK.
        assertEquals(ThemeSetting.SYSTEM, vm.uiState.value.theme)
    }

    @Test fun `when the dynamic color write fails, the state keeps showing the stored value`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val vm = GeneralSettingsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.dynamicColor)

        fake.failWrites = true
        vm.setDynamicColor(true)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.dynamicColor)
    }
}

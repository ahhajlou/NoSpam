// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.model.ThemeSetting
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SettingsRepository.hasAppearanceSettings], written from its KDoc
 * -- whether the user has ever changed an appearance setting (theme or
 * dynamic color, stored in PreferenceFile.UI_SETTINGS) -- independently of
 * the implementation.
 */
class SettingsRepositoryHasAppearanceTest {

    @Test fun `false on a fresh store`() {
        val repo = SettingsRepository(FakePreferencesDataSource())
        assertFalse(repo.hasAppearanceSettings())
    }

    @Test fun `true after setTheme`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setTheme(ThemeSetting.DARK)
        assertTrue(repo.hasAppearanceSettings())
    }

    @Test fun `true after setDynamicColor`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setDynamicColor(true)
        assertTrue(repo.hasAppearanceSettings())
    }

    @Test fun `stays false after changing only non-appearance settings`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSpamProtection(false)
        repo.setBackfillPending(true)
        assertFalse(repo.hasAppearanceSettings())
    }

    @Test fun `true when UI_SETTINGS already has contents, as on an upgraded install`() {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("theme" to "DARK")),
        )
        val repo = SettingsRepository(fake)
        assertTrue(repo.hasAppearanceSettings())
    }
}

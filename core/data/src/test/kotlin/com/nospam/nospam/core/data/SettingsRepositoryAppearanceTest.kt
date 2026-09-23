// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.model.ThemeSetting
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SettingsRepository's appearance settings (theme, dynamic color),
 * written from the CLAUDE.md-linked spec -- key names, defaults, and failure
 * policy -- not from the implementation.
 */
class SettingsRepositoryAppearanceTest {

    @Test fun `theme defaults to SYSTEM when absent`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `theme flow emits the stored value and follows changes`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            repo.setTheme(ThemeSetting.DARK)
            assertEquals(ThemeSetting.DARK, awaitItem())
            repo.setTheme(ThemeSetting.LIGHT)
            assertEquals(ThemeSetting.LIGHT, awaitItem())
        }
    }

    @Test fun `setTheme stores the enum name under the documented key in UI_SETTINGS`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setTheme(ThemeSetting.DARK)
        assertEquals("DARK", fake.contents(PreferenceFile.UI_SETTINGS)["theme"])
    }

    @Test fun `a pre-existing stored theme is honoured`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("theme" to "LIGHT")),
        )
        val repo = SettingsRepository(fake)
        repo.theme.test {
            assertEquals(ThemeSetting.LIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an unrecognised stored theme reads as SYSTEM`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("theme" to "PURPLE")),
        )
        val repo = SettingsRepository(fake)
        repo.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a non-String value under the theme key reads as SYSTEM`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("theme" to 3)),
        )
        val repo = SettingsRepository(fake)
        repo.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed reads make theme emit SYSTEM`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        repo.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed writes leave setTheme returning normally and storing nothing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = SettingsRepository(fake)
        repo.setTheme(ThemeSetting.DARK)
        assertTrue(fake.contents(PreferenceFile.UI_SETTINGS)["theme"] != "DARK")
    }

    @Test fun `dynamicColor defaults to false when absent`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.dynamicColor.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `dynamicColor flow emits the stored value and follows changes`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.dynamicColor.test {
            assertFalse(awaitItem())
            repo.setDynamicColor(true)
            assertTrue(awaitItem())
            repo.setDynamicColor(false)
            assertFalse(awaitItem())
        }
    }

    @Test fun `setDynamicColor stores under the documented key in UI_SETTINGS`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setDynamicColor(true)
        assertEquals(true, fake.contents(PreferenceFile.UI_SETTINGS)["dynamic_color"])
    }

    @Test fun `a pre-existing stored dynamic color is honoured`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("dynamic_color" to true)),
        )
        val repo = SettingsRepository(fake)
        repo.dynamicColor.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a non-Boolean value under the dynamic color key reads as false`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.UI_SETTINGS to mapOf("dynamic_color" to "yes")),
        )
        val repo = SettingsRepository(fake)
        repo.dynamicColor.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed reads make dynamicColor emit false`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        repo.dynamicColor.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed writes leave setDynamicColor returning normally and storing nothing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = SettingsRepository(fake)
        repo.setDynamicColor(true)
        assertTrue(fake.contents(PreferenceFile.UI_SETTINGS)["dynamic_color"] != true)
    }

    @Test fun `writing theme does not touch the SETTINGS file`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setTheme(ThemeSetting.DARK)
        assertTrue(fake.contents(PreferenceFile.SETTINGS).isEmpty())
    }

    @Test fun `writing dynamic color does not touch the SETTINGS file`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setDynamicColor(true)
        assertTrue(fake.contents(PreferenceFile.SETTINGS).isEmpty())
    }

    @Test fun `setSpamProtection does not change UI_SETTINGS`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSpamProtection(false)
        assertTrue(fake.contents(PreferenceFile.UI_SETTINGS).isEmpty())
    }
}

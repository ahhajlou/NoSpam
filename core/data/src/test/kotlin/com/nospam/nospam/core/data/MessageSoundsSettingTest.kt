// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SettingsRepository.messageSounds / setMessageSounds, written from
 * the task spec (key "message_sounds" in PreferenceFile.SETTINGS, default
 * true, non-Boolean and failed reads fall back to true, writes are scoped to
 * their own key and file) independently of the implementation.
 */
class MessageSoundsSettingTest {

    @Test fun `message sounds defaults to true when absent`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.messageSounds.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `messageSounds flow emits the stored value and follows changes`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.messageSounds.test {
            assertEquals(true, awaitItem())
            repo.setMessageSounds(false)
            assertEquals(false, awaitItem())
            repo.setMessageSounds(true)
            assertEquals(true, awaitItem())
        }
    }

    @Test fun `a pre-existing stored false is honoured`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("message_sounds" to false)),
        )
        val repo = SettingsRepository(fake)
        repo.messageSounds.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a pre-existing stored true is honoured`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("message_sounds" to true)),
        )
        val repo = SettingsRepository(fake)
        repo.messageSounds.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setMessageSounds stores under the documented key`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setMessageSounds(false)
        assertEquals(false, fake.contents(PreferenceFile.SETTINGS)["message_sounds"])
    }

    @Test fun `setMessageSounds round trips`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setMessageSounds(false)
        assertEquals(false, fake.contents(PreferenceFile.SETTINGS)["message_sounds"])
        repo.setMessageSounds(true)
        assertEquals(true, fake.contents(PreferenceFile.SETTINGS)["message_sounds"])
    }

    @Test fun `failed reads make messageSounds emit true`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        repo.messageSounds.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a value of the wrong type under the message sounds key reads as true`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("message_sounds" to "no")),
        )
        val repo = SettingsRepository(fake)
        repo.messageSounds.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed writes leave setMessageSounds returning normally and storing nothing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = SettingsRepository(fake)
        repo.setMessageSounds(false)
        assertTrue(fake.contents(PreferenceFile.SETTINGS)["message_sounds"] != false)
    }

    @Test fun `setMessageSounds does not touch other keys in the settings file`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("spam_protection_enabled" to false, "install_id" to "abc")),
        )
        val repo = SettingsRepository(fake)
        repo.setMessageSounds(false)
        val contents = fake.contents(PreferenceFile.SETTINGS)
        assertEquals(false, contents["spam_protection_enabled"])
        assertEquals("abc", contents["install_id"])
        assertEquals(false, contents["message_sounds"])
    }

    @Test fun `setMessageSounds does not touch the ui settings file`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setMessageSounds(false)
        assertTrue(fake.contents(PreferenceFile.UI_SETTINGS).isEmpty())
        assertFalse(fake.exists(PreferenceFile.UI_SETTINGS))
    }
}

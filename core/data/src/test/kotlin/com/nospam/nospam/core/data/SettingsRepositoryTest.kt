// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tests for SettingsRepository written from the CLAUDE.md-linked spec (key
 * names, defaults, failure policy), not from the implementation.
 */
class SettingsRepositoryTest {

    @Test fun `spam protection defaults to true when absent`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        assertTrue(repo.isSpamProtectionEnabled())
    }

    @Test fun `spamProtection flow emits the stored value and follows changes`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.spamProtection.test {
            assertEquals(true, awaitItem())
            repo.setSpamProtection(false)
            assertEquals(false, awaitItem())
            repo.setSpamProtection(true)
            assertEquals(true, awaitItem())
        }
    }

    @Test fun `isSpamProtectionEnabled returns the current value after set`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setSpamProtection(false)
        assertFalse(repo.isSpamProtectionEnabled())
    }

    @Test fun `a pre-existing stored false is honoured`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("spam_protection_enabled" to false)),
        )
        val repo = SettingsRepository(fake)
        assertFalse(repo.isSpamProtectionEnabled())
    }

    @Test fun `setSpamProtection stores under the documented key`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSpamProtection(false)
        assertEquals(false, fake.contents(PreferenceFile.SETTINGS)["spam_protection_enabled"])
    }

    @Test fun `backfill pending defaults to false`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        assertFalse(repo.isBackfillPending())
    }

    @Test fun `backfill pending round trips through set and is`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.setBackfillPending(true)
        assertTrue(repo.isBackfillPending())
        repo.setBackfillPending(false)
        assertFalse(repo.isBackfillPending())
    }

    @Test fun `setBackfillPending stores under the documented key`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setBackfillPending(true)
        assertEquals(true, fake.contents(PreferenceFile.SETTINGS)["history_backfill_pending"])
    }

    @Test fun `installId mints a UUID on first call and returns it on later calls`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        val first = repo.installId()
        // Must be a valid UUID string.
        assertNotNull(UUID.fromString(first))
        val second = repo.installId()
        assertEquals(first, second)
    }

    @Test fun `installId is stored under the documented key`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val id = repo.installId()
        assertEquals(id, fake.contents(PreferenceFile.SETTINGS)["install_id"])
    }

    @Test fun `a pre-existing stored install id is returned unchanged`() = runTest {
        val existing = UUID.randomUUID().toString()
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("install_id" to existing)),
        )
        val repo = SettingsRepository(fake)
        assertEquals(existing, repo.installId())
    }

    @Test fun `many concurrent first calls to installId all get the same id`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        val results = (1..20).map { async { repo.installId() } }.awaitAll()
        assertEquals(1, results.toSet().size)
    }

    @Test fun `failed reads make spamProtection emit true`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        repo.spamProtection.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed reads make isSpamProtectionEnabled return true`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        assertTrue(repo.isSpamProtectionEnabled())
    }

    @Test fun `failed reads make isBackfillPending return false`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        assertFalse(repo.isBackfillPending())
    }

    @Test fun `failed writes leave setSpamProtection returning normally and storing nothing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = SettingsRepository(fake)
        repo.setSpamProtection(false)
        assertTrue(fake.contents(PreferenceFile.SETTINGS)["spam_protection_enabled"] != false)
    }

    @Test fun `failed writes still let installId return a valid UUID without throwing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = SettingsRepository(fake)
        val id = repo.installId()
        assertNotNull(UUID.fromString(id))
    }

    @Test fun `a value of the wrong type under spam protection key reads as the default`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("spam_protection_enabled" to "no")),
        )
        val repo = SettingsRepository(fake)
        assertTrue(repo.isSpamProtectionEnabled())
    }
}

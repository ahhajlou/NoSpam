// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.model.SwipeAction
import com.nospam.nospam.core.model.SwipeActions
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests for SettingsRepository's swipeActions/setSwipeActions, written from
 * the CLAUDE.md-linked spec (storage file SETTINGS, key names swipe_right /
 * swipe_left as enum names, ARCHIVE/ARCHIVE defaults, per-side failure
 * policy), independently of the implementation.
 */
class SwipeActionsSettingsTest {

    @Test fun `defaults to archive-archive when nothing is stored`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.swipeActions.test {
            assertEquals(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.ARCHIVE), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a pre-existing stored value is honoured for both sides`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("swipe_right" to "DELETE", "swipe_left" to "TOGGLE_READ")),
        )
        val repo = SettingsRepository(fake)
        repo.swipeActions.test {
            assertEquals(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.TOGGLE_READ), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an unknown stored value reads as the default for that side only`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("swipe_right" to "NOT_A_REAL_ACTION", "swipe_left" to "DELETE")),
        )
        val repo = SettingsRepository(fake)
        repo.swipeActions.test {
            assertEquals(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.DELETE), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a non-String stored value reads as the default for that side only`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("swipe_right" to 7, "swipe_left" to "NONE")),
        )
        val repo = SettingsRepository(fake)
        repo.swipeActions.test {
            assertEquals(SwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.NONE), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSwipeActions stores both sides under the documented keys as enum names`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSwipeActions(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.NONE))
        assertEquals("DELETE", fake.contents(PreferenceFile.SETTINGS)["swipe_right"])
        assertEquals("NONE", fake.contents(PreferenceFile.SETTINGS)["swipe_left"])
    }

    @Test fun `setSwipeActions writes to SETTINGS and never creates or touches UI_SETTINGS`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        repo.setSwipeActions(SwipeActions(right = SwipeAction.TOGGLE_READ, left = SwipeAction.ARCHIVE))
        assertFalse(fake.exists(PreferenceFile.UI_SETTINGS))
        assertEquals(emptyMap<String, Any>(), fake.contents(PreferenceFile.UI_SETTINGS))
    }

    @Test fun `swipeActions round-trips through setSwipeActions`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = SettingsRepository(fake)
        val actions = SwipeActions(right = SwipeAction.NONE, left = SwipeAction.TOGGLE_READ)
        repo.setSwipeActions(actions)
        repo.swipeActions.test {
            assertEquals(actions, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `swipeActions emits again after being set`() = runTest {
        val repo = SettingsRepository(FakePreferencesDataSource())
        repo.swipeActions.test {
            assertEquals(SwipeActions(), awaitItem())
            repo.setSwipeActions(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.DELETE))
            assertEquals(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.DELETE), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed reads make swipeActions emit the archive-archive default`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = SettingsRepository(fake)
        repo.swipeActions.test {
            assertEquals(SwipeActions(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSwipeActions leaves other keys already in SETTINGS untouched`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(PreferenceFile.SETTINGS to mapOf("spam_protection_enabled" to false, "install_id" to "abc-123")),
        )
        val repo = SettingsRepository(fake)
        repo.setSwipeActions(SwipeActions(right = SwipeAction.DELETE, left = SwipeAction.NONE))
        val contents = fake.contents(PreferenceFile.SETTINGS)
        assertEquals(false, contents["spam_protection_enabled"])
        assertEquals("abc-123", contents["install_id"])
        assertEquals("DELETE", contents["swipe_right"])
        assertEquals("NONE", contents["swipe_left"])
    }
}

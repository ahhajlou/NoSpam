// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DraftRepository written from the CLAUDE.md-linked spec (key
 * format, blank-removes semantics, failure policy), not from the
 * implementation.
 */
class DraftRepositoryTest {

    @Test fun `load returns null when there is no draft`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        assertNull(repo.load(1L))
    }

    @Test fun `load returns what was saved`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        repo.save(1L, "hello")
        assertEquals("hello", repo.load(1L))
    }

    @Test fun `drafts for different threads are independent`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        repo.save(1L, "one")
        repo.save(2L, "two")
        assertEquals("one", repo.load(1L))
        assertEquals("two", repo.load(2L))
    }

    @Test fun `saving empty text removes the draft`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        repo.save(1L, "hello")
        repo.save(1L, "")
        assertNull(repo.load(1L))
    }

    @Test fun `saving whitespace-only text removes the draft`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        repo.save(1L, "hello")
        repo.save(1L, "   \n\t ")
        assertNull(repo.load(1L))
    }

    @Test fun `save stores under the documented key format`() = runTest {
        val fake = FakePreferencesDataSource()
        val repo = DraftRepository(fake)
        repo.save(42L, "draft text")
        assertEquals("draft text", fake.contents(PreferenceFile.DRAFTS)["draft_42"])
    }

    @Test fun `observeAll maps thread id to text`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        repo.save(1L, "one")
        repo.save(2L, "two")
        repo.observeAll().test {
            assertEquals(mapOf(1L to "one", 2L to "two"), awaitItem())
        }
    }

    @Test fun `observeAll reflects saves and removals`() = runTest {
        val repo = DraftRepository(FakePreferencesDataSource())
        repo.observeAll().test {
            assertEquals(emptyMap<Long, String>(), awaitItem())
            repo.save(1L, "one")
            assertEquals(mapOf(1L to "one"), awaitItem())
            repo.save(2L, "two")
            assertEquals(mapOf(1L to "one", 2L to "two"), awaitItem())
            repo.save(1L, "")
            assertEquals(mapOf(2L to "two"), awaitItem())
        }
    }

    @Test fun `observeAll ignores keys that are not draft_ plus a long`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(
                PreferenceFile.DRAFTS to mapOf(
                    "draft_1" to "one",
                    "spam_protection_enabled" to true,
                    "draft_notanumber" to "ignored",
                    "draftmissingunderscore" to "ignored too",
                ),
            ),
        )
        val repo = DraftRepository(fake)
        repo.observeAll().test {
            assertEquals(mapOf(1L to "one"), awaitItem())
        }
    }

    @Test fun `observeAll ignores a draft key whose value is not a String`() = runTest {
        val fake = FakePreferencesDataSource(
            initial = mapOf(
                PreferenceFile.DRAFTS to mapOf(
                    "draft_1" to "one",
                    "draft_2" to 123,
                ),
            ),
        )
        val repo = DraftRepository(fake)
        repo.observeAll().test {
            assertEquals(mapOf(1L to "one"), awaitItem())
        }
    }

    @Test fun `failed reads make load return null`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = DraftRepository(fake)
        assertNull(repo.load(1L))
    }

    @Test fun `failed reads make observeAll emit an empty map`() = runTest {
        val fake = FakePreferencesDataSource().apply { failReads = true }
        val repo = DraftRepository(fake)
        repo.observeAll().test {
            assertEquals(emptyMap<Long, String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed writes make save return normally without storing`() = runTest {
        val fake = FakePreferencesDataSource().apply { failWrites = true }
        val repo = DraftRepository(fake)
        repo.save(1L, "hello")
        assertTrue(fake.contents(PreferenceFile.DRAFTS).isEmpty())
    }
}

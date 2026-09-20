// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SpamPreferences had zero tests before Wave 2A (task brief + feature-settings.md).
 * DataStore needs a real filesystem, so these run under Robolectric.
 *
 * `Context.spamDataStore` is a single top-level `by preferencesDataStore(...)`
 * delegate, i.e. a process/classloader-wide DataStore *singleton* -- once any
 * Context successfully resolves it, every other Context (even a distinct
 * fake one) reuses the same underlying file for the rest of that test's
 * classloader lifetime. That means "two independent installs get different
 * ids" cannot be demonstrated within a single test method/class here (no
 * production bug -- a genuine testability constraint of the singleton-delegate
 * pattern, not something this suite can fake around). The failure-path tests
 * that need a *first-ever* access to actually throw live in the dedicated
 * SpamPreferencesFailureTest class instead, so no earlier test in this class
 * can have already pinned a working DataStore file first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpamPreferencesTest {

    private fun freshContext(): Context {
        // Each test gets its own isolated files dir so DataStore starts empty.
        val base: Context = ApplicationProvider.getApplicationContext()
        val dir = File(base.cacheDir, "spam-prefs-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
            override fun getApplicationContext(): Context = this
        }
    }

    @Test fun `flow defaults to true before any write`() = runTest {
        val ctx = freshContext()
        assertEquals(true, SpamPreferences.flow(ctx).first())
    }

    @Test fun `isEnabled defaults to true before any write`() = runTest {
        val ctx = freshContext()
        assertTrue(SpamPreferences.isEnabled(ctx))
    }

    @Test fun `setEnabled and flow round-trip`() = runTest {
        val ctx = freshContext()
        SpamPreferences.setEnabled(ctx, false)
        assertEquals(false, SpamPreferences.flow(ctx).first())
        SpamPreferences.setEnabled(ctx, true)
        assertEquals(true, SpamPreferences.flow(ctx).first())
    }

    @Test fun `isBackfillPending defaults to false before any write`() = runTest {
        val ctx = freshContext()
        assertFalse(SpamPreferences.isBackfillPending(ctx))
    }

    @Test fun `setBackfillPending round-trip`() = runTest {
        val ctx = freshContext()
        SpamPreferences.setBackfillPending(ctx, true)
        assertTrue(SpamPreferences.isBackfillPending(ctx))
        SpamPreferences.setBackfillPending(ctx, false)
        assertFalse(SpamPreferences.isBackfillPending(ctx))
    }

    @Test fun `installId is idempotent across two calls`() = runTest {
        val ctx = freshContext()
        val first = SpamPreferences.installId(ctx)
        val second = SpamPreferences.installId(ctx)
        assertEquals(first, second)
    }

    @Test fun `installId returns a valid UUID string`() = runTest {
        val ctx = freshContext()
        val id = SpamPreferences.installId(ctx)
        // Must not throw.
        UUID.fromString(id)
    }

    @Test fun `concurrent first-time callers converge on one minted id`() = runTest {
        val ctx = freshContext()
        val ids = (1..10).map { async { SpamPreferences.installId(ctx) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
    }
}

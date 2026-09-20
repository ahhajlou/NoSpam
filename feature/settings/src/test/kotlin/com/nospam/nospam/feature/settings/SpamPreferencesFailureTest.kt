// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fail-safe behaviour of SpamPreferences when the underlying DataStore cannot
 * be read/written, per feature-settings.md. Kept in its own test class (see
 * SpamPreferencesTest's class doc): `Context.spamDataStore` is a
 * classloader-wide singleton, so this class must be the *first* thing in its
 * classloader to touch it -- every test here uses a Context whose
 * `getFilesDir()` throws outright, so the very first access is guaranteed to
 * fail rather than risk reusing an already-working DataStore pinned by an
 * earlier test elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpamPreferencesFailureTest {

    private fun brokenContext(): Context {
        val base: Context = ApplicationProvider.getApplicationContext()
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = throw IllegalStateException("simulated filesystem failure")
            override fun getApplicationContext(): Context = this
        }
    }

    @Test fun `isEnabled fails open to true when the datastore throws`() = runTest {
        assertTrue(SpamPreferences.isEnabled(brokenContext()))
    }

    @Test fun `isBackfillPending fails closed to false when the datastore throws`() = runTest {
        assertFalse(SpamPreferences.isBackfillPending(brokenContext()))
    }

    @Test fun `setBackfillPending swallows a write failure without throwing`() = runTest {
        val ctx = brokenContext()
        // Must not throw even though the underlying write cannot succeed.
        SpamPreferences.setBackfillPending(ctx, true)
        // The failed write leaves the fail-safe default in place.
        assertFalse(SpamPreferences.isBackfillPending(ctx))
    }

    @Test fun `installId falls back to a fresh unpersisted UUID on every call when the datastore throws`() = runTest {
        val ctx = brokenContext()
        val first = SpamPreferences.installId(ctx)
        val second = SpamPreferences.installId(ctx)
        UUID.fromString(first)
        UUID.fromString(second)
        // Not idempotent under permanent failure: each call mints a new id.
        assertNotEquals(first, second)
    }
}

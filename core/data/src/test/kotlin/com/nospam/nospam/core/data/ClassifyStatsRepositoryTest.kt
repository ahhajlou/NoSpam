// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.model.ClassifyStats
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifyStatsRepositoryTest {
    private var clock = 1_000L
    private val prefs = FakePreferencesDataSource()
    private val repo = ClassifyStatsRepository(prefs) { clock }

    @Test fun `nothing counted is zeros without a start`() = runTest {
        assertEquals(ClassifyStats(), repo.stats.first())
    }

    @Test fun `each event adds one to its own count`() = runTest {
        repo.increment(ClassifyEvent.CHECKED)
        repo.increment(ClassifyEvent.CHECKED)
        repo.increment(ClassifyEvent.LATE)
        repo.increment(ClassifyEvent.FAILED)
        repo.increment(ClassifyEvent.LEAKED)

        repo.stats.test {
            assertEquals(ClassifyStats(since = 1_000L, checked = 2, late = 1, failed = 1, leaked = 1), awaitItem())
        }
    }

    @Test fun `since is the first count and does not move`() = runTest {
        repo.increment(ClassifyEvent.CHECKED)
        clock = 5_000L
        repo.increment(ClassifyEvent.CHECKED)

        assertEquals(1_000L, repo.stats.first().since)
    }

    @Test fun `reset zeroes and the next count starts a new period`() = runTest {
        repo.increment(ClassifyEvent.LATE)
        repo.reset()
        assertEquals(ClassifyStats(), repo.stats.first())
        assertNull(repo.stats.first().since)

        clock = 9_000L
        repo.increment(ClassifyEvent.LATE)
        assertEquals(ClassifyStats(since = 9_000L, late = 1), repo.stats.first())
    }

    @Test fun `a failed read gives zeros`() = runTest {
        repo.increment(ClassifyEvent.CHECKED)
        prefs.failReads = true

        assertEquals(ClassifyStats(), repo.stats.first())
    }

    @Test fun `a failed write is dropped`() = runTest {
        prefs.failWrites = true

        repo.increment(ClassifyEvent.CHECKED)
        repo.reset()
    }
}

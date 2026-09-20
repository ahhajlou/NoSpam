// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real device or emulator (`./gradlew :core:database:connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class SqlitePinnedDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqlitePinnedDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqlitePinnedDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun pin_then_isPinned_and_observeAll_agree() = runTest {
        assertFalse(dao.isPinned(1L))
        dao.pin(1L)
        assertTrue(dao.isPinned(1L))
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun pinning_the_same_thread_twice_is_idempotent() = runTest {
        dao.pin(1L)
        dao.pin(1L)
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun unpin_removes_exactly_that_thread() = runTest {
        dao.pin(1L)
        dao.pin(2L)
        dao.unpin(1L)
        assertFalse(dao.isPinned(1L))
        assertTrue(dao.isPinned(2L))
        assertEquals(listOf(2L), dao.observeAll().first().map { it.threadId })
    }
}

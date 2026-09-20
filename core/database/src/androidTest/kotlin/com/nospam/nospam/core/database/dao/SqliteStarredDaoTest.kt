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
class SqliteStarredDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteStarredDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteStarredDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun star_then_isStarred_and_observeAll_agree() = runTest {
        assertFalse(dao.isStarred(1L))
        dao.star(1L)
        assertTrue(dao.isStarred(1L))
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun starring_the_same_thread_twice_is_idempotent() = runTest {
        dao.star(1L)
        dao.star(1L)
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun unstar_removes_exactly_that_thread() = runTest {
        dao.star(1L)
        dao.star(2L)
        dao.unstar(1L)
        assertFalse(dao.isStarred(1L))
        assertTrue(dao.isStarred(2L))
        assertEquals(listOf(2L), dao.observeAll().first().map { it.threadId })
    }
}

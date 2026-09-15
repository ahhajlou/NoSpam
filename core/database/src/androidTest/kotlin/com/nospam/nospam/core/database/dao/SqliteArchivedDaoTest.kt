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
class SqliteArchivedDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteArchivedDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteArchivedDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun archive_then_isArchived_and_observeAll_agree() = runTest {
        assertFalse(dao.isArchived(1L))
        dao.archive(1L)
        assertTrue(dao.isArchived(1L))
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun archiving_the_same_thread_twice_is_idempotent() = runTest {
        dao.archive(1L)
        dao.archive(1L)
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun unarchive_removes_exactly_that_thread() = runTest {
        dao.archive(1L)
        dao.archive(2L)
        dao.unarchive(1L)
        assertFalse(dao.isArchived(1L))
        assertTrue(dao.isArchived(2L))
        assertEquals(listOf(2L), dao.observeAll().first().map { it.threadId })
    }
}

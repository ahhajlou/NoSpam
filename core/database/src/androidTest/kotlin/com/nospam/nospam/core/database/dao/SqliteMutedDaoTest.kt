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
class SqliteMutedDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteMutedDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteMutedDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun mute_then_isMuted_and_observeAll_agree() = runTest {
        assertFalse(dao.isMuted(1L))
        dao.mute(1L)
        assertTrue(dao.isMuted(1L))
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun muting_the_same_thread_twice_is_idempotent() = runTest {
        dao.mute(1L)
        dao.mute(1L)
        assertEquals(listOf(1L), dao.observeAll().first().map { it.threadId })
    }

    @Test
    fun unmute_removes_exactly_that_thread() = runTest {
        dao.mute(1L)
        dao.mute(2L)
        dao.unmute(1L)
        assertFalse(dao.isMuted(1L))
        assertTrue(dao.isMuted(2L))
        assertEquals(listOf(2L), dao.observeAll().first().map { it.threadId })
    }
}

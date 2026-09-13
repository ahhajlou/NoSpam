package com.nospam.nospam.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real device or emulator (`./gradlew :core:database:connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class SqliteSpamVerdictDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteSpamVerdictDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteSpamVerdictDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun upsert_then_getByThread_round_trips_regardless_of_isSpam() = runTest {
        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = false, score = -1.0))
        assertEquals(false, dao.getByThread(1)?.isSpam)
    }

    @Test
    fun observeSpam_only_contains_isSpam_true_rows() = runTest {
        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0))
        dao.upsert(SpamVerdictEntity(threadId = 2, isSpam = false, score = -1.0))
        assertEquals(listOf(1L), dao.observeSpam().first().map { it.threadId })
    }

    @Test
    fun upserting_a_spam_thread_as_ham_removes_it_from_observeSpam_but_getByThread_still_finds_it() = runTest {
        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0))
        assertEquals(listOf(1L), dao.observeSpam().first().map { it.threadId })

        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = false, score = -1.0))
        assertTrue(dao.observeSpam().first().isEmpty())
        assertEquals(false, dao.getByThread(1)?.isSpam)
    }

    @Test
    fun deleteByThread_removes_the_row_entirely() = runTest {
        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0))
        dao.deleteByThread(1)
        assertNull(dao.getByThread(1))
    }

    @Test
    fun clearAutoSpam_keeps_user_overrides() = runTest {
        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0, isUserOverride = false))
        dao.upsert(SpamVerdictEntity(threadId = 2, isSpam = true, score = 1.0, isUserOverride = true))
        dao.clearAutoSpam()
        assertNull(dao.getByThread(1))
        assertEquals(true, dao.getByThread(2)?.isSpam)
    }

    @Test
    fun deleteAutoSpamOlderThan_spares_overrides_and_recent_rows() = runTest {
        dao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0, isUserOverride = false, updatedAt = 100L))
        dao.upsert(SpamVerdictEntity(threadId = 2, isSpam = true, score = 1.0, isUserOverride = true, updatedAt = 100L))
        dao.upsert(SpamVerdictEntity(threadId = 3, isSpam = true, score = 1.0, isUserOverride = false, updatedAt = 9_000L))

        val removed = dao.deleteAutoSpamOlderThan(1_000L)

        assertEquals(1, removed)
        assertNull(dao.getByThread(1))
        assertEquals(true, dao.getByThread(2)?.isUserOverride)
        assertEquals(true, dao.getByThread(3)?.isSpam)
    }
}

package com.nospam.nospam.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
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
class SqliteSenderStateDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteSenderStateDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteSenderStateDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    private fun state(address: String, state: ThreadSpamState, spam: Int = 0, ham: Int = 0, override: Boolean = false) =
        SenderStateEntity(normalizedAddress = address, state = state, spamCount = spam, hamCount = ham, isUserOverride = override)

    @Test
    fun getByAddress_returns_null_when_absent() = runTest {
        assertNull(dao.getByAddress("+98912"))
    }

    @Test
    fun upsert_then_getByAddress_round_trips() = runTest {
        dao.upsert(state("+98912", ThreadSpamState.SPAM, spam = 3, ham = 1))
        val found = dao.getByAddress("+98912")
        assertEquals(ThreadSpamState.SPAM, found?.state)
        assertEquals(3, found?.spamCount)
        assertEquals(1, found?.hamCount)
    }

    @Test
    fun upsert_replaces_the_row_keyed_by_normalizedAddress() = runTest {
        dao.upsert(state("+98912", ThreadSpamState.CLEAN))
        dao.upsert(state("+98912", ThreadSpamState.SPAM, spam = 1))
        assertEquals(1, dao.getAll().size)
        assertEquals(ThreadSpamState.SPAM, dao.getByAddress("+98912")?.state)
    }

    @Test
    fun upsertAll_is_one_atomic_batch_and_keeps_untouched_rows() = runTest {
        dao.upsert(state("+98911", ThreadSpamState.CLEAN))
        dao.upsertAll(listOf(state("+98922", ThreadSpamState.SPAM), state("+98933", ThreadSpamState.MIXED)))
        val all = dao.observeAll().first()
        assertEquals(3, all.size)
        assertTrue(all.any { it.normalizedAddress == "+98911" && it.state == ThreadSpamState.CLEAN })
    }

    @Test
    fun upsertAll_with_an_empty_list_is_a_no_op() = runTest {
        dao.upsert(state("+98911", ThreadSpamState.CLEAN))
        dao.upsertAll(emptyList())
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun deleteByAddress_removes_exactly_that_row() = runTest {
        dao.upsert(state("+98911", ThreadSpamState.CLEAN))
        dao.upsert(state("+98922", ThreadSpamState.SPAM))
        dao.deleteByAddress("+98911")
        assertEquals(listOf("+98922"), dao.observeAll().first().map { it.normalizedAddress })
    }
}

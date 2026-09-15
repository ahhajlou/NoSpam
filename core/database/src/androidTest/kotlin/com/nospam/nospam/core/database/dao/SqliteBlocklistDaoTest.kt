package com.nospam.nospam.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.BlocklistEntity
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
class SqliteBlocklistDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteBlocklistDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteBlocklistDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun insert_then_findByAddress_round_trips() = runTest {
        dao.insert(BlocklistEntity(address = "+98912", reason = "spam", createdAt = 1L))
        val found = dao.findByAddress("+98912")
        assertEquals("+98912", found?.address)
        assertEquals("spam", found?.reason)
    }

    @Test
    fun findByAddress_returns_null_when_absent() = runTest {
        assertNull(dao.findByAddress("+98999"))
    }

    @Test
    fun insert_replaces_the_row_with_the_same_unique_address() = runTest {
        dao.insert(BlocklistEntity(address = "+98912", reason = "first", createdAt = 1L))
        dao.insert(BlocklistEntity(address = "+98912", reason = "second", createdAt = 2L))
        val all = dao.observeAll().first()
        assertEquals(1, all.count { it.address == "+98912" })
        assertEquals("second", all.first { it.address == "+98912" }.reason)
    }

    @Test
    fun observeAll_reflects_delete_by_id_and_by_address() = runTest {
        dao.insert(BlocklistEntity(address = "+98911", createdAt = 1L))
        dao.insert(BlocklistEntity(address = "+98922", createdAt = 2L))
        val afterInsert = dao.observeAll().first()
        assertEquals(2, afterInsert.size)

        val toDeleteById = afterInsert.first { it.address == "+98911" }
        dao.delete(toDeleteById)
        assertEquals(listOf("+98922"), dao.observeAll().first().map { it.address })

        dao.deleteByAddress("+98922")
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun observeAll_is_ordered_by_createdAt_descending() = runTest {
        dao.insert(BlocklistEntity(address = "+98911", createdAt = 1L))
        dao.insert(BlocklistEntity(address = "+98922", createdAt = 2L))
        dao.insert(BlocklistEntity(address = "+98933", createdAt = 3L))
        assertEquals(listOf("+98933", "+98922", "+98911"), dao.observeAll().first().map { it.address })
    }
}

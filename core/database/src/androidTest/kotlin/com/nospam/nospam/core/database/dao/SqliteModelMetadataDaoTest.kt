package com.nospam.nospam.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.ModelMetadataEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real device or emulator (`./gradlew :core:database:connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class SqliteModelMetadataDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteModelMetadataDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteModelMetadataDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun get_returns_null_before_any_upsert() = runTest {
        assertNull(dao.get())
    }

    @Test
    fun upsert_then_get_round_trips() = runTest {
        val entity = ModelMetadataEntity(version = "1.0.0", threshold = 0.5, updatedAt = 100L)
        dao.upsert(entity)
        assertEquals(entity, dao.get())
    }

    @Test
    fun a_second_upsert_replaces_the_single_row() = runTest {
        dao.upsert(ModelMetadataEntity(version = "1.0.0"))
        dao.upsert(ModelMetadataEntity(version = "2.0.0"))
        assertEquals("2.0.0", dao.get()?.version)
    }
}

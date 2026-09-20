// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device or emulator
 * (`./gradlew :core:database:connectedDebugAndroidTest`). Compiled by CI on
 * every build; not executed in this environment (no emulator here).
 *
 * Exercises the actual `SQLiteOpenHelper` schema creation, since `core:database`'s
 * unit tests can only cover the pure `InMemory*Dao`/`FlowDeltas` logic
 * (CLAUDE.md §5's testing-gap note applies here too: `android.database` needs a
 * real device).
 */
@RunWith(AndroidJUnit4::class)
class SqliteNoSpamOpenHelperTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun onCreate_creates_every_table() {
        val helper = SqliteNoSpamOpenHelper(context)
        val db = helper.writableDatabase
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        val expected = setOf(
            "blocklist", "spam_verdict", "archived_threads", "model_metadata",
            "message_verdict", "sender_state", "starred_threads", "pinned_threads",
            "muted_threads",
        )
        assertEquals(expected, tables.intersect(expected))
        helper.close()
    }

    @Test
    fun database_version_is_4() {
        val helper = SqliteNoSpamOpenHelper(context)
        assertEquals(4, helper.writableDatabase.version)
        helper.close()
    }

    @Test
    fun reopening_the_same_database_preserves_data() {
        val first = SqliteNoSpamOpenHelper(context)
        val values = android.content.ContentValues().apply {
            put("address", "+98912")
            put("reason", "test")
            put("createdAt", 1L)
        }
        first.writableDatabase.insert("blocklist", null, values)
        first.close()

        val second = SqliteNoSpamOpenHelper(context)
        second.readableDatabase.query(
            "blocklist", null, "address = ?", arrayOf("+98912"), null, null, null,
        ).use { c -> assertEquals(1, c.count) }
        second.close()
    }
}

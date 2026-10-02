// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.model.ThreadSpamState
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
    fun database_version_is_5() {
        val helper = SqliteNoSpamOpenHelper(context)
        assertEquals(5, helper.writableDatabase.version)
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

    /**
     * Version 5 re-derives automatic sender states under the new routing model.
     * Built by hand as a version 4 file, the way an installed app has it.
     */
    @Test
    fun upgrade_to_5_rederives_automatic_states_and_leaves_user_decisions() {
        val path = context.getDatabasePath("nospam.db")
        path.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """CREATE TABLE sender_state (
                    normalizedAddress TEXT PRIMARY KEY,
                    state TEXT NOT NULL,
                    spamCount INTEGER NOT NULL,
                    hamCount INTEGER NOT NULL,
                    isUserOverride INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )"""
            )
            fun row(address: String, state: String, spam: Int, ham: Int, override: Int = 0) =
                db.execSQL("INSERT INTO sender_state VALUES ('$address', '$state', $spam, $ham, $override, 1)")
            row("graduated", "SPAM", 3, 1)        // ratio rule hid a sender that sent ham
            row("first", "SPAM", 1, 0)            // one spam message hid a new sender
            row("repeat", "SPAM", 4, 0)           // only ever spam: stays hidden
            row("protected", "MIXED", 2, 0)       // protected as a contact or replied-to
            row("reported", "SPAM", 1, 0, 1)      // the user's Report spam
            row("allowed", "TRUSTED", 5, 0, 1)    // the user's Not spam
            row("blocked", "BLOCKED", 2, 0)
            db.version = 4
        }

        val helper = SqliteNoSpamOpenHelper(context)
        val states = mutableMapOf<String, Pair<String, Int>>()
        helper.readableDatabase.rawQuery("SELECT normalizedAddress, state, hasReplied FROM sender_state", null).use { c ->
            while (c.moveToNext()) states[c.getString(0)] = c.getString(1) to c.getInt(2)
        }
        helper.close()

        assertEquals(ThreadSpamState.MIXED.name to 0, states["graduated"])
        assertEquals(ThreadSpamState.SUSPECTED.name to 0, states["first"])
        assertEquals(ThreadSpamState.SPAM.name to 0, states["repeat"])
        assertEquals(ThreadSpamState.MIXED.name to 1, states["protected"])
        assertEquals(ThreadSpamState.SPAM.name to 0, states["reported"])
        assertEquals(ThreadSpamState.TRUSTED.name to 0, states["allowed"])
        assertEquals(ThreadSpamState.BLOCKED.name to 0, states["blocked"])
    }
}

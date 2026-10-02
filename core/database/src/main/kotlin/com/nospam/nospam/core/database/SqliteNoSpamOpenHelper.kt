// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState

class SqliteNoSpamOpenHelper(context: Context) :
    SQLiteOpenHelper(context, "nospam.db", null, 5) {

    private companion object {
        /**
         * `message_verdict` is the one table that grows without bound: auto-spam
         * rows are kept indefinitely on purpose (CLAUDE.md §15), so only auto-ham
         * is pruned. Both of its non-primary-key access paths were full scans.
         *
         * `messageId` is INTEGER PRIMARY KEY, so it is the rowid and needs no
         * index; the same is true of every `threadId INTEGER PRIMARY KEY` table.
         * `blocklist.address` is UNIQUE and `sender_state.normalizedAddress` is a
         * TEXT primary key, both of which SQLite indexes already.
         */
        val INDEXES = listOf(
            // getByThread / deleteByThread
            "CREATE INDEX IF NOT EXISTS idx_message_verdict_threadId " +
                "ON message_verdict(threadId)",
            // deleteAutoOlderThan: userLabel IS NULL AND isSpam = 0 AND createdAt < ?
            // Runs opportunistically on every received SMS.
            "CREATE INDEX IF NOT EXISTS idx_message_verdict_retention " +
                "ON message_verdict(isSpam, userLabel, createdAt)",
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE blocklist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL UNIQUE,
                reason TEXT,
                createdAt INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE spam_verdict (
                threadId INTEGER PRIMARY KEY,
                isSpam INTEGER NOT NULL,
                score REAL NOT NULL,
                isUserOverride INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE archived_threads (
                threadId INTEGER PRIMARY KEY
            )"""
        )
        db.execSQL(
            """CREATE TABLE model_metadata (
                id INTEGER PRIMARY KEY,
                version TEXT NOT NULL,
                threshold REAL NOT NULL,
                updatedAt INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE message_verdict (
                messageId INTEGER PRIMARY KEY,
                threadId INTEGER NOT NULL,
                normalizedAddress TEXT NOT NULL,
                isSpam INTEGER NOT NULL,
                score REAL NOT NULL,
                createdAt INTEGER NOT NULL,
                userLabel INTEGER
            )"""
        )
        db.execSQL(
            """CREATE TABLE sender_state (
                normalizedAddress TEXT PRIMARY KEY,
                state TEXT NOT NULL,
                spamCount INTEGER NOT NULL,
                hamCount INTEGER NOT NULL,
                isUserOverride INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                hasReplied INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("""CREATE TABLE starred_threads (threadId INTEGER PRIMARY KEY)""")
        db.execSQL("""CREATE TABLE pinned_threads (threadId INTEGER PRIMARY KEY)""")
        db.execSQL("""CREATE TABLE muted_threads (threadId INTEGER PRIMARY KEY)""")
        INDEXES.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS message_verdict (
                    messageId INTEGER PRIMARY KEY,
                    threadId INTEGER NOT NULL,
                    normalizedAddress TEXT NOT NULL,
                    isSpam INTEGER NOT NULL,
                    score REAL NOT NULL,
                    createdAt INTEGER NOT NULL,
                    userLabel INTEGER
                )"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS sender_state (
                    normalizedAddress TEXT PRIMARY KEY,
                    state TEXT NOT NULL,
                    spamCount INTEGER NOT NULL,
                    hamCount INTEGER NOT NULL,
                    isUserOverride INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )"""
            )
        }
        if (oldVersion < 3) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS starred_threads (threadId INTEGER PRIMARY KEY)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS pinned_threads (threadId INTEGER PRIMARY KEY)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS muted_threads (threadId INTEGER PRIMARY KEY)""")
        }
        if (oldVersion < 4) {
            INDEXES.forEach(db::execSQL)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE sender_state ADD COLUMN hasReplied INTEGER NOT NULL DEFAULT 0")
            rederiveSenderStates(db)
        }
    }

    /**
     * Version 5 replaced the spam graduation ratio with the routing model in
     * `ThreadSpamPolicy` (TODO.md, "Spam routing — agreed model"). Automatic
     * states written by the old rules are re-derived from their counts, or a
     * sender the old rules hid (one spam message, or a ratio that later ham
     * could not undo) would stay hidden. The user's own decisions and blocks
     * are left alone.
     *
     * A MIXED row with no ham got there only through the old protection for
     * contacts and replied-to senders. It is marked as replied so that it stays
     * in the inbox: for a contact that is more than true, but it can only hide
     * less, and the classifier is no longer consulted for contacts anyway.
     *
     * The upgrade never hides a conversation that was showing: a row that
     * would newly become SPAM keeps its old state.
     */
    private fun rederiveSenderStates(db: SQLiteDatabase) {
        val automatic = listOf(ThreadSpamState.CLEAN, ThreadSpamState.MIXED, ThreadSpamState.SPAM).map { it.name }
        val rows = mutableListOf<Triple<String, ThreadSpamState, Boolean>>()
        db.query(
            "sender_state",
            arrayOf("normalizedAddress", "state", "spamCount", "hamCount"),
            "isUserOverride = 0 AND state IN (?, ?, ?)",
            automatic.toTypedArray(),
            null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                val old = ThreadSpamState.valueOf(c.getString(1))
                val spam = c.getInt(2)
                val ham = c.getInt(3)
                val replied = old == ThreadSpamState.MIXED && ham == 0
                val derived = ThreadSpamPolicy.deriveState(spam, ham, replied)
                val state = if (derived == ThreadSpamState.SPAM && old != ThreadSpamState.SPAM) old else derived
                if (state != old || replied) rows.add(Triple(c.getString(0), state, replied))
            }
        }
        for ((address, state, replied) in rows) {
            db.update(
                "sender_state",
                android.content.ContentValues().apply {
                    put("state", state.name)
                    if (replied) put("hasReplied", 1)
                },
                "normalizedAddress = ?",
                arrayOf(address),
            )
        }
    }
}

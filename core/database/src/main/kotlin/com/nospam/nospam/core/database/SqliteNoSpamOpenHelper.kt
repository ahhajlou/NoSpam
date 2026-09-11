package com.nospam.nospam.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SqliteNoSpamOpenHelper(context: Context) :
    SQLiteOpenHelper(context, "nospam.db", null, 4) {

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
                updatedAt INTEGER NOT NULL
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
    }
}

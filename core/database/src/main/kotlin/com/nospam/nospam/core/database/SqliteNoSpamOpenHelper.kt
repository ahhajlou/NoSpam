package com.nospam.nospam.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SqliteNoSpamOpenHelper(context: Context) :
    SQLiteOpenHelper(context, "nospam.db", null, 2) {

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
    }
}

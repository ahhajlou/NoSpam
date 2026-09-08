package com.nospam.nospam.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SqliteNoSpamOpenHelper(context: Context) :
    SQLiteOpenHelper(context, "nospam.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No migrations yet; v1 is initial.
    }
}

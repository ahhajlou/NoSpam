// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Batch writes for the threadId-keyed flag tables (archived, pinned, starred,
 * muted), each in one transaction: a multi-select action either lands for
 * every thread or for none, and the DAO publishes its flow once instead of once
 * per thread.
 */
internal fun SQLiteDatabase.insertThreadIds(table: String, threadIds: Collection<Long>) {
    inTransaction {
        for (id in threadIds) {
            val values = ContentValues().apply { put("threadId", id) }
            insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
}

/** Deletes in chunks, because SQLite caps the number of bound parameters. */
internal fun SQLiteDatabase.deleteThreadIds(table: String, threadIds: Collection<Long>) {
    inTransaction {
        threadIds.chunked(MAX_BOUND_IDS).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            delete(table, "threadId IN ($placeholders)", chunk.map { it.toString() }.toTypedArray())
        }
    }
}

private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

/** Well under SQLite's default limit of 999 bound parameters on older devices. */
private const val MAX_BOUND_IDS = 500

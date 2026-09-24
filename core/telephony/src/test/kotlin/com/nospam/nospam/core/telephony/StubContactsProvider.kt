// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract

/**
 * Just enough of the contacts provider for [ContactLookup]: the phone table,
 * read whole, and PhoneLookup, which filters by the number in its path. Every
 * read throws until [granted], as the real one does without READ_CONTACTS.
 */
class StubContactsProvider : ContentProvider() {
    @Volatile var granted = true
    @Volatile var queries = 0

    /** Number to display name. */
    val contacts = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        queries++
        if (!granted) throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        val rows = if (uri.pathSegments.firstOrNull() == "phone_lookup") {
            contacts.filterKeys { it == uri.lastPathSegment }
        } else {
            contacts
        }
        rows.entries.forEachIndexed { index, (number, name) ->
            cursor.addRow(columns.map { column ->
                when (column) {
                    ContactsContract.CommonDataKinds.Phone.NUMBER -> number
                    ContactsContract.PhoneLookup.DISPLAY_NAME -> name
                    ContactsContract.PhoneLookup._ID, ContactsContract.CommonDataKinds.Phone.CONTACT_ID -> index + 1L
                    ContactsContract.PhoneLookup.STARRED -> 0
                    else -> null
                }
            })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

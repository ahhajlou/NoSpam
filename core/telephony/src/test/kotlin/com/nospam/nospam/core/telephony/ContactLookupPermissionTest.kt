// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A contacts read that fails because READ_CONTACTS is not held yet must not be
 * remembered. Onboarding grants the permission in the running process, and a
 * remembered failure left contacts nameless, and treated by ingress as
 * strangers, until the process restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactLookupPermissionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var provider: StubContactsProvider

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(StubContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    @Test fun `a lookup denied before the grant is not remembered as a miss`() {
        val lookup = ContactLookup(context)
        assertNull(lookup.lookup(CONTACT_NUMBER))

        provider.granted = true
        assertEquals(CONTACT_NAME, lookup.lookup(CONTACT_NUMBER)?.displayName)
    }

    @Test fun `a directory load denied before the grant is retried`() {
        val lookup = ContactLookup(context)
        lookup.warm()
        assertFalse(lookup.isWarm)

        provider.granted = true
        lookup.warm()
        assertTrue(lookup.isWarm)
        assertEquals(CONTACT_NAME, lookup.lookup(CONTACT_NUMBER)?.displayName)
    }

    @Test fun `a real miss is still remembered`() {
        provider.granted = true
        val lookup = ContactLookup(context)
        assertNull(lookup.lookup("+15550009999"))
        val queries = provider.queries
        assertNull(lookup.lookup("+15550009999"))
        assertEquals(queries, provider.queries)
    }

    /** One contact; every read throws until [granted], as the provider does without READ_CONTACTS. */
    class StubContactsProvider : ContentProvider() {
        @Volatile var granted = false
        @Volatile var queries = 0

        override fun onCreate() = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            queries++
            if (!granted) throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            val columns = projection ?: emptyArray()
            val cursor = MatrixCursor(columns)
            val isPhoneLookup = uri.pathSegments.firstOrNull() == "phone_lookup"
            // PhoneLookup filters by the number in its path; the phone table is read whole.
            if (isPhoneLookup && uri.lastPathSegment != CONTACT_NUMBER) return cursor
            cursor.addRow(columns.map { column ->
                when (column) {
                    ContactsContract.CommonDataKinds.Phone.NUMBER -> CONTACT_NUMBER
                    ContactsContract.PhoneLookup.DISPLAY_NAME -> CONTACT_NAME
                    ContactsContract.PhoneLookup._ID, ContactsContract.CommonDataKinds.Phone.CONTACT_ID -> 7L
                    ContactsContract.PhoneLookup.STARRED -> 0
                    else -> null
                }
            })
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val CONTACT_NUMBER = "+15551110001"
        const val CONTACT_NAME = "NoSpam QA Contact"
    }
}

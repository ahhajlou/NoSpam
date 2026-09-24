// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import android.os.Looper
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A change in the contacts provider reaches [ContactLookup] while the process
 * runs. Contacts were read once per process, so a contact added while the app
 * ran stayed a bare number, and a stranger to ingress, until a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactLookupChangeTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var provider: StubContactsProvider

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(StubContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    /** What the provider does after any write: announce it under its authority. */
    private fun providerChanged() {
        context.contentResolver.notifyChange(ContactsContract.RawContacts.CONTENT_URI, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a contact added after a miss is found once the provider announces it`() {
        val lookup = ContactLookup(context)
        assertNull(lookup.lookup(NUMBER))

        provider.contacts[NUMBER] = "Added Later"
        providerChanged()

        assertEquals("Added Later", lookup.lookup(NUMBER)?.displayName)
    }

    @Test fun `the directory is reloaded after a change`() {
        val lookup = ContactLookup(context)
        lookup.warm()
        assertNull(lookup.lookup(NUMBER))

        provider.contacts[NUMBER] = "Added Later"
        providerChanged()
        lookup.warm()

        assertEquals("Added Later", lookup.lookup(NUMBER)?.displayName)
    }

    @Test fun `a renamed contact reads under its new name`() {
        provider.contacts[NUMBER] = "Old Name"
        val lookup = ContactLookup(context)
        lookup.warm()
        assertEquals("Old Name", lookup.lookup(NUMBER)?.displayName)

        provider.contacts[NUMBER] = "New Name"
        providerChanged()
        lookup.warm()

        assertEquals("New Name", lookup.lookup(NUMBER)?.displayName)
    }

    @Test fun `a deleted contact is no longer found`() {
        provider.contacts[NUMBER] = "Gone Soon"
        val lookup = ContactLookup(context)
        lookup.warm()
        assertEquals("Gone Soon", lookup.lookup(NUMBER)?.displayName)

        provider.contacts.remove(NUMBER)
        providerChanged()
        lookup.warm()

        assertNull(lookup.lookup(NUMBER))
    }

    @Test fun `every change moves the generation on`() {
        val lookup = ContactLookup(context)
        val before = lookup.generation.value
        providerChanged()
        providerChanged()
        assertEquals(before + 2, lookup.generation.value)
    }

    @Test fun `without a change, lookups stay cached`() {
        val lookup = ContactLookup(context)
        lookup.warm()
        lookup.lookup(NUMBER)
        val queries = provider.queries
        lookup.warm()
        lookup.lookup(NUMBER)
        assertEquals(queries, provider.queries)
    }

    private companion object {
        const val NUMBER = "+15551110002"
    }
}

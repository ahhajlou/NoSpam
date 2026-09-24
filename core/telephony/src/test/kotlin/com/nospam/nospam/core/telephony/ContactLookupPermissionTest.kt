// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
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
        provider.granted = false
        provider.contacts[CONTACT_NUMBER] = CONTACT_NAME
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

    private companion object {
        const val CONTACT_NUMBER = "+15551110001"
        const val CONTACT_NAME = "NoSpam QA Contact"
    }
}

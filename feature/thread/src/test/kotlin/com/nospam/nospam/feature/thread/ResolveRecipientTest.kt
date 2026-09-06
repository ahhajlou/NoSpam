package com.nospam.nospam.feature.thread

import org.junit.Assert.*
import org.junit.Test

class ResolveRecipientTest {
    @Test fun `blank query resolves to nothing`() {
        assertNull(resolveRecipientAddress(""))
        assertNull(resolveRecipientAddress("   "))
    }

    @Test fun `exact contact name resolves to phone`() {
        assertEquals("5550144", resolveRecipientAddress("Ben Carter"))
    }

    @Test fun `partial contact name resolves to phone`() {
        assertEquals("5550144", resolveRecipientAddress("ben"))
    }

    @Test fun `raw number passes through trimmed`() {
        assertEquals("+989121234567", resolveRecipientAddress("  +989121234567  "))
    }
}

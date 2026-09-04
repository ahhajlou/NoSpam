package com.example.nospam.core.model

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun `ThreadId value class holds value`() {
        val id = ThreadId(42)
        assertEquals(42L, id.value)
    }
    @Test fun `SpamVerdict isSpam derived`() {
        assertTrue(SpamVerdict(SpamLabel.SPAM, 1.2).isSpam)
        assertFalse(SpamVerdict(SpamLabel.HAM, -1.2).isSpam)
    }
    @Test fun `Conversation defaults`() {
        val c = Conversation(ThreadId(1), emptyList(), "hi", 0L, 1, false)
        assertFalse(c.isSpam)
        assertFalse(c.isArchived)
    }
    @Test fun `TelephonyConstants are plain strings`() {
        assertEquals("android.provider.Telephony.SMS_DELIVER", TelephonyConstants.ACTION_SMS_DELIVER)
    }
}

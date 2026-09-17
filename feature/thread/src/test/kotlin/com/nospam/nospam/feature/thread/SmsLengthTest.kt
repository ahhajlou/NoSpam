package com.nospam.nospam.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsLengthTest {

    @Test fun `an empty draft is no message at all`() {
        val length = smsLength("")
        assertEquals(0, length.segments)
        assertEquals(160, length.remainingInSegment)
    }

    @Test fun `plain latin text fits 160 characters in one part`() {
        assertEquals(SmsLength(1, 159, unicode = false), smsLength("a"))
        assertEquals(SmsLength(1, 0, unicode = false), smsLength("a".repeat(160)))
    }

    @Test fun `one character past the limit costs two parts of 153`() {
        val length = smsLength("a".repeat(161))
        assertEquals(2, length.segments)
        // Two concatenated parts hold 306; 161 used leaves 145.
        assertEquals(145, length.remainingInSegment)
    }

    @Test fun `persian text switches to unicode, where a part is 70 characters`() {
        val length = smsLength("سلام")
        assertTrue(length.unicode)
        assertEquals(1, length.segments)
        assertEquals(66, length.remainingInSegment)
    }

    @Test fun `71 unicode characters need two parts of 67`() {
        val length = smsLength("ا".repeat(71))
        assertEquals(2, length.segments)
        assertEquals(63, length.remainingInSegment)
    }

    @Test fun `a single emoji halves the limit for the whole message`() {
        val latinOnly = smsLength("a".repeat(70))
        assertFalse(latinOnly.unicode)
        assertEquals(1, latinOnly.segments)

        val withEmoji = smsLength("a".repeat(70) + "🙂")
        assertTrue(withEmoji.unicode)
        // 70 letters plus a surrogate pair is 72 UTF-16 units: two parts.
        assertEquals(2, withEmoji.segments)
    }

    @Test fun `gsm extension characters cost two septets each`() {
        // '€' is in the GSM extension table, so it is encoded as escape + char.
        assertEquals(SmsLength(1, 158, unicode = false), smsLength("€"))
        assertFalse(smsLength("{}[]").unicode)
        assertEquals(152, smsLength("{}[]").remainingInSegment)
    }

    @Test fun `newlines and common punctuation stay in the gsm alphabet`() {
        val length = smsLength("Hi!\nHow are you? (fine) 50% @home")
        assertFalse(length.unicode)
        assertEquals(1, length.segments)
    }
}

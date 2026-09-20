package com.nospam.nospam.feature.thread

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Emoji are inserted where the cursor is, not appended to the end. */
class ComposeBarInsertTest {

    @Test fun `insert at the cursor keeps both sides of the text`() {
        val value = TextFieldValue("hello world", TextRange(5))
        val result = value.insert("🙂")
        assertEquals("hello🙂 world", result.text)
    }

    @Test fun `the cursor lands after the inserted text`() {
        val result = TextFieldValue("ab", TextRange(1)).insert("XY")
        assertEquals(TextRange(3), result.selection)
    }

    @Test fun `inserting replaces the current selection`() {
        val value = TextFieldValue("hello world", TextRange(0, 5))
        val result = value.insert("bye")
        assertEquals("bye world", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test fun `inserting into an empty field yields just the emoji`() {
        val result = TextFieldValue("").insert("🎉")
        assertEquals("🎉", result.text)
        assertEquals(TextRange(2), result.selection) // one surrogate pair
    }
}

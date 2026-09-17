package com.nospam.nospam.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentLogicTest {

    @Test fun `avatar initial is the first letter, upper-cased`() {
        assertEquals("A", avatarInitial("alice"))
        assertEquals("B", avatarInitial("  Bob Smith"))
    }

    @Test fun `avatar initial skips leading non-letters`() {
        assertEquals("M", avatarInitial("😀 Mom"))
        assertEquals("S", avatarInitial("+SHOP"))
    }

    @Test fun `avatar initial works for Persian names`() {
        assertEquals("ع", avatarInitial("علی"))
    }

    @Test fun `phone numbers and blanks have no initial, so the icon shows`() {
        assertNull(avatarInitial("+989121234567"))
        assertNull(avatarInitial("0912 123 4567"))
        assertNull(avatarInitial(""))
        assertNull(avatarInitial(null))
    }

    @Test fun `palette index is stable and in range`() {
        val key = "+989121234567"
        val first = avatarPaletteIndex(key, 6)
        assertEquals(first, avatarPaletteIndex(key, 6))
        listOf("a", "b", "+1", "IRANCELL", "", key).forEach {
            assertTrue(avatarPaletteIndex(it, 6) in 0 until 6)
        }
    }

    private fun action(label: String, withIcon: Boolean = true) =
        TopBarAction(label = label, icon = if (withIcon) Icons.Filled.Delete else null, onClick = {})

    @Test fun `up to maxInline plus one icon actions all render inline when nothing overflows`() {
        val actions = listOf(action("a"), action("b"), action("c"))
        val (inline, overflow) = partitionTopBarActions(actions, maxInline = 2)
        assertEquals(actions, inline)
        assertTrue(overflow.isEmpty())
    }

    @Test fun `beyond that, maxInline stay inline and the rest overflow in order`() {
        val actions = listOf(action("a"), action("b"), action("c"), action("d"))
        val (inline, overflow) = partitionTopBarActions(actions, maxInline = 2)
        assertEquals(listOf("a", "b"), inline.map { it.label })
        assertEquals(listOf("c", "d"), overflow.map { it.label })
    }

    @Test fun `icon-less actions always overflow`() {
        val actions = listOf(action("a"), action("text only", withIcon = false))
        val (inline, overflow) = partitionTopBarActions(actions, maxInline = 2)
        assertEquals(listOf("a"), inline.map { it.label })
        assertEquals(listOf("text only"), overflow.map { it.label })
    }

    @Test fun `no actions means nothing inline and no overflow menu`() {
        val (inline, overflow) = partitionTopBarActions(emptyList(), maxInline = 2)
        assertTrue(inline.isEmpty())
        assertTrue(overflow.isEmpty())
    }
}

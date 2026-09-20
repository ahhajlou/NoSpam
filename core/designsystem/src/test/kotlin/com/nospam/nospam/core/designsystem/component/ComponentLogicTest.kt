// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `no more than maxInline icons, even when the rest would fit`() {
        val actions = listOf(action("a"), action("b"), action("c"))
        val (inline, overflow) = partitionTopBarActions(actions, maxInline = 2)
        assertEquals(listOf("a", "b"), inline.map { it.label })
        assertEquals(listOf("c"), overflow.map { it.label })
    }

    @Test fun `fewer actions than slots all render inline with no menu`() {
        val (inline, overflow) = partitionTopBarActions(listOf(action("a"), action("b")), maxInline = 3)
        assertEquals(listOf("a", "b"), inline.map { it.label })
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

    @Test fun `selection bar with three inline slots overflows from the fourth action`() {
        val actions = listOf(action("pin"), action("archive"), action("delete"), action("read"), action("star"))
        val (inline, overflow) = partitionTopBarActions(actions, maxInline = 3)
        assertEquals(listOf("pin", "archive", "delete"), inline.map { it.label })
        assertEquals(listOf("read", "star"), overflow.map { it.label })
    }

    @Test fun `toggle adds an unselected id and removes a selected one`() {
        assertEquals(setOf(1L), toggledSelection(emptySet(), 1))
        assertEquals(setOf(1L, 2L), toggledSelection(setOf(1L), 2))
        assertEquals(setOf(2L), toggledSelection(setOf(1L, 2L), 1))
    }

    @Test fun `selection is active only while something is selected`() {
        val selection = SelectionState(emptySet())
        assertFalse(selection.isActive)
        selection.toggle(5)
        assertTrue(selection.isActive)
        selection.toggle(5)
        assertFalse(selection.isActive)
    }

    @Test fun `retainVisible drops ids that left the list and keeps the rest`() {
        val selection = SelectionState(setOf(1L, 2L, 3L))
        selection.retainVisible(setOf(2L, 3L, 4L))
        assertEquals(setOf(2L, 3L), selection.ids)
        selection.retainVisible(emptySet())
        assertFalse(selection.isActive)
    }
}

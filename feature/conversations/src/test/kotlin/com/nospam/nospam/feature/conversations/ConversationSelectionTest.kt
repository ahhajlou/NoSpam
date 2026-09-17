package com.nospam.nospam.feature.conversations

import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSelectionTest {

    private fun conv(
        id: Long,
        address: String = "+100$id",
        read: Boolean = true,
        pinned: Boolean = false,
        starred: Boolean = false,
        muted: Boolean = false,
        blocked: Boolean = false,
    ) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant(address)),
        snippet = "s$id",
        date = 0,
        messageCount = 1,
        read = read,
        isPinned = pinned,
        isStarred = starred,
        isMuted = muted,
        isBlocked = blocked,
    )

    @Test fun `toggle adds an unselected id and removes a selected one`() {
        assertEquals(setOf(1L), toggled(emptySet(), 1))
        assertEquals(setOf(1L, 2L), toggled(setOf(1L), 2))
        assertEquals(setOf(2L), toggled(setOf(1L, 2L), 1))
    }

    @Test fun `selection is active only while something is selected`() {
        val selection = ConversationSelection(emptySet())
        assertFalse(selection.isActive)
        selection.toggle(5)
        assertTrue(selection.isActive)
        selection.toggle(5)
        assertFalse(selection.isActive)
    }

    @Test fun `retainVisible drops ids that left the list and keeps the rest`() {
        val selection = ConversationSelection(setOf(1L, 2L, 3L))
        selection.retainVisible(setOf(2L, 3L, 4L))
        assertEquals(setOf(2L, 3L), selection.ids)
        selection.retainVisible(emptySet())
        assertFalse(selection.isActive)
    }

    @Test fun `pin action unpins only when every selected row is pinned`() {
        assertTrue(summarize(listOf(conv(1, pinned = true), conv(2, pinned = true))).allPinned)
        assertFalse(summarize(listOf(conv(1, pinned = true), conv(2))).allPinned)
    }

    @Test fun `star and mute follow the same all-or-change rule`() {
        val mixed = summarize(listOf(conv(1, starred = true, muted = true), conv(2)))
        assertFalse(mixed.allStarred)
        assertFalse(mixed.allMuted)
        val all = summarize(listOf(conv(1, starred = true, muted = true), conv(2, starred = true, muted = true)))
        assertTrue(all.allStarred)
        assertTrue(all.allMuted)
    }

    @Test fun `any unread row makes the read action mark as read`() {
        assertTrue(summarize(listOf(conv(1), conv(2, read = false))).anyUnread)
        assertFalse(summarize(listOf(conv(1), conv(2))).anyUnread)
    }

    @Test fun `unblock is offered only when every selected sender is blocked`() {
        assertTrue(summarize(listOf(conv(1, blocked = true), conv(2, blocked = true))).allBlocked)
        assertFalse(summarize(listOf(conv(1, blocked = true), conv(2))).allBlocked)
    }

    @Test fun `an empty selection claims no shared flag`() {
        val summary = summarize(emptyList())
        assertEquals(0, summary.count)
        assertFalse(summary.allPinned || summary.allStarred || summary.allMuted || summary.allBlocked || summary.anyUnread)
        assertNull(summary.singleAddress)
    }

    @Test fun `single-item actions get an address only for exactly one selected row`() {
        assertEquals("+1001", summarize(listOf(conv(1))).singleAddress)
        assertNull(summarize(listOf(conv(1), conv(2))).singleAddress)
    }

    @Test fun `addresses are de-duplicated so one sender is blocked once`() {
        val sameSender = listOf(conv(1, address = "+98912"), conv(2, address = "+98912"), conv(3, address = "SHOP"))
        assertEquals(listOf("+98912", "SHOP"), addressesOf(sameSender))
    }
}

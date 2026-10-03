// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Written from the spec for [threadRouteFrom] without reading it: a route
 * carries the participant only for a shown one-to-one conversation.
 */
class ThreadRouteFromTest {

    private fun conversation(id: Long, vararg participants: Participant) = Conversation(
        threadId = ThreadId(id),
        participants = participants.toList(),
        snippet = "s",
        date = 1L,
        messageCount = 1,
        read = true,
    )

    private val ali = Participant("+989121234567", displayName = "Ali", photoUri = "content://photo/1")
    private val sara = Participant("+989351112222", displayName = "Sara")

    @Test fun `one participant carries address, name and photo`() {
        val route = threadRouteFrom(7L, listOf(conversation(3L, sara), conversation(7L, ali)))
        assertEquals(
            ThreadRoute(7L, address = "+989121234567", contactName = "Ali", contactPhotoUri = "content://photo/1"),
            route,
        )
    }

    @Test fun `participant without a name or photo carries only the address`() {
        val route = threadRouteFrom(3L, listOf(conversation(3L, Participant("MCI"))))
        assertEquals(ThreadRoute(3L, address = "MCI"), route)
    }

    @Test fun `null list gives a bare route`() {
        assertEquals(ThreadRoute(7L), threadRouteFrom(7L, null))
    }

    @Test fun `empty list gives a bare route`() {
        assertEquals(ThreadRoute(7L), threadRouteFrom(7L, emptyList()))
    }

    @Test fun `thread not shown gives a bare route`() {
        assertEquals(ThreadRoute(7L), threadRouteFrom(7L, listOf(conversation(3L, ali))))
    }

    @Test fun `group conversation gives a bare route`() {
        assertEquals(ThreadRoute(7L), threadRouteFrom(7L, listOf(conversation(7L, ali, sara))))
    }

    @Test fun `conversation with no participants gives a bare route`() {
        assertEquals(ThreadRoute(7L), threadRouteFrom(7L, listOf(conversation(7L))))
    }

    @Test fun `forward body is always null`() {
        assertEquals(null, threadRouteFrom(7L, listOf(conversation(7L, ali))).forwardBody)
        assertEquals(null, threadRouteFrom(7L, null).forwardBody)
    }
}

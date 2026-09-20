// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

import org.junit.Assert.*
import org.junit.Test

class ThreadSpamPolicyTest {
    private fun senderState(state: ThreadSpamState, spam: Int = 0, ham: Int = 0, override: Boolean = false, addr: String = "0912") =
        SenderState(addr, state, spam, ham, override)

    @Test fun `any isBlocked to BLOCKED NONE`() {
        val prev = senderState(ThreadSpamState.CLEAN, ham = 1)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = false, isBlocked = true))
        assertEquals(ThreadSpamState.BLOCKED, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    @Test fun `TRUSTED stays TRUSTED NORMAL`() {
        val prev = senderState(ThreadSpamState.TRUSTED, override = true)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.TRUSTED, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    /**
     * TRUSTED is honoured on the state alone. Previously the guard also required
     * isUserOverride, so a TRUSTED sender without it fell through to a branch
     * that happened to return the same thing for ham but reached it by accident,
     * and answered SILENT rather than NORMAL for spam.
     */
    @Test fun `TRUSTED without the override flag is still trusted`() {
        val prev = senderState(ThreadSpamState.TRUSTED, ham = 5, override = false)
        val ham = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = false))
        assertEquals(ThreadSpamState.TRUSTED, ham.newState.state)
        assertEquals(NotificationDecision.NORMAL, ham.notification)

        val spam = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.TRUSTED, spam.newState.state)
        assertEquals(NotificationDecision.NORMAL, spam.notification)
    }

    @Test fun `SPAM sticky ignores later ham`() {
        val prev = senderState(ThreadSpamState.SPAM, spam = 1)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = false))
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    @Test fun `SPAM sticky ignores later spam`() {
        val prev = senderState(ThreadSpamState.SPAM, spam = 2)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
    }

    @Test fun `new sender ham to CLEAN NORMAL`() {
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(null, isSpam = false))
        assertEquals(ThreadSpamState.CLEAN, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    @Test fun `new sender spam not contact to SPAM NONE`() {
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(null, isSpam = true, isContact = false))
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    @Test fun `new sender spam is contact to MIXED SILENT`() {
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(null, isSpam = true, isContact = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    @Test fun `CLEAN spam to MIXED SILENT`() {
        val prev = senderState(ThreadSpamState.CLEAN, ham = 2)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    @Test fun `MIXED spam otherwise stays MIXED SILENT`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 1, ham = 2)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    /**
     * Pins the boundary to the constants rather than to the literals 3 and 0.8,
     * so changing the policy is a one-line change in one place and this test
     * keeps asserting the rule instead of a number.
     */
    @Test fun `graduation needs one spam short of the minimum to still be MIXED`() {
        val prev = senderState(
            ThreadSpamState.MIXED,
            spam = ThreadSpamPolicy.GRADUATION_MIN_SPAM - 2, // +1 below the bar
            ham = 0,
        )
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    @Test fun `graduation happens exactly at the minimum spam count and ratio`() {
        val prev = senderState(
            ThreadSpamState.MIXED,
            spam = ThreadSpamPolicy.GRADUATION_MIN_SPAM - 1, // +1 reaches the bar
            ham = 0,
        )
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertTrue(out.newState.spamCount >= ThreadSpamPolicy.GRADUATION_MIN_SPAM)
    }

    @Test fun `MIXED spam graduates at 3 and 0_8`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 2, ham = 0) // after +1 =3 spam, 3 total, ratio 1.0
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    @Test fun `ratio graduation needs 0_8`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 2, ham = 2) // after +1 =3 spam, 5 total, 0.6
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    @Test fun `contact never auto spam`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 2, ham = 0)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true, isContact = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    @Test fun `replied sender never auto spam`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 2, ham = 0)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = true, hasOutbound = true))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    /**
     * The anti-false-positive rule is that a sender the user has replied to is
     * never auto-promoted to SPAM. `decide` honours that for a sender it has
     * seen before, via `protectFromSpam = isContact || hasOutbound`, but the
     * brand-new-sender branch checks `isContact` alone.
     *
     * Reachable in practice: text a business first, get a promotional reply.
     * No `SenderState` row exists yet, so `prevState` is null and `hasOutbound`
     * is true. The conversation lands in Spam with no notification.
     */
    @Test fun `new sender spam from a replied-to address is never auto spam`() {
        val out = ThreadSpamPolicy.decideWithAddress(
            "0912",
            PolicyInput(prevState = null, isSpam = true, isContact = false, hasOutbound = true),
        )
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    @Test fun `CLEAN ham stays CLEAN`() {
        val prev = senderState(ThreadSpamState.CLEAN, ham = 1)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = false))
        assertEquals(ThreadSpamState.CLEAN, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    @Test fun `mixed never flaps back to clean on single ham`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 1, ham = 1)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = false))
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    @Test fun `blocked wins over everything`() {
        val prev = senderState(ThreadSpamState.TRUSTED, override = true)
        val out = ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam = false, isBlocked = true))
        assertEquals(ThreadSpamState.BLOCKED, out.newState.state)
    }
}

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

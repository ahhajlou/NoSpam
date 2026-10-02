// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

import org.junit.Assert.*
import org.junit.Test

/** The routing table in TODO.md, "Spam routing — agreed model". */
class ThreadSpamPolicyTest {
    private fun senderState(
        state: ThreadSpamState,
        spam: Int = 0,
        ham: Int = 0,
        override: Boolean = false,
        replied: Boolean = false,
        addr: String = "0912",
    ) = SenderState(addr, state, spam, ham, override, hasReplied = replied)

    private fun decide(prev: SenderState?, isSpam: Boolean, isContact: Boolean = false, hasOutbound: Boolean = false, isBlocked: Boolean = false) =
        ThreadSpamPolicy.decideWithAddress("0912", PolicyInput(prev, isSpam, isContact, hasOutbound, isBlocked))

    /** Feeds [verdicts] in order to a new sender and returns the final output. */
    private fun replay(vararg verdicts: Boolean, hasOutbound: Boolean = false): PolicyOutput {
        var out: PolicyOutput? = null
        for (isSpam in verdicts) out = decide(out?.newState, isSpam, hasOutbound = hasOutbound)
        return out!!
    }

    // Row 1: blocked

    @Test fun `any isBlocked to BLOCKED NONE`() {
        val out = decide(senderState(ThreadSpamState.CLEAN, ham = 1), isSpam = false, isBlocked = true)
        assertEquals(ThreadSpamState.BLOCKED, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    @Test fun `blocked wins over everything`() {
        val out = decide(senderState(ThreadSpamState.TRUSTED, override = true), isSpam = false, isBlocked = true, isContact = true)
        assertEquals(ThreadSpamState.BLOCKED, out.newState.state)
    }

    // Row 2: the user's own decisions

    @Test fun `TRUSTED stays TRUSTED NORMAL`() {
        val out = decide(senderState(ThreadSpamState.TRUSTED, override = true), isSpam = true)
        assertEquals(ThreadSpamState.TRUSTED, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    @Test fun `TRUSTED without the override flag is still trusted`() {
        val prev = senderState(ThreadSpamState.TRUSTED, ham = 5, override = false)
        assertEquals(ThreadSpamState.TRUSTED, decide(prev, isSpam = true).newState.state)
        assertEquals(NotificationDecision.NORMAL, decide(prev, isSpam = true).notification)
    }

    @Test fun `a user's Report spam is not undone by a ham message`() {
        val prev = senderState(ThreadSpamState.SPAM, spam = 1, override = true)
        val out = decide(prev, isSpam = false)
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    @Test fun `a reply does not undo the user's Report spam`() {
        val prev = senderState(ThreadSpamState.SPAM, spam = 1, override = true)
        val out = decide(prev, isSpam = true, hasOutbound = true)
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertFalse(out.newState.hasReplied)
    }

    // Row 3: contacts bypass the classifier

    @Test fun `a contact's message changes nothing and notifies normally, whatever the verdict`() {
        val prev = senderState(ThreadSpamState.CLEAN, ham = 2)
        val out = decide(prev, isSpam = true, isContact = true)
        assertEquals(prev, out.newState)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    @Test fun `a new contact sender starts CLEAN with no counts`() {
        val out = decide(null, isSpam = true, isContact = true)
        assertEquals(ThreadSpamState.CLEAN, out.newState.state)
        assertEquals(0, out.newState.spamCount)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    // Row 3: replied-to senders are classified and labelled, never hidden

    @Test fun `spam from a replied-to sender is labelled in the inbox, never hidden`() {
        val out = replay(true, true, true, hasOutbound = true)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
        assertTrue(out.newState.hasReplied)
    }

    @Test fun `a reply stays recorded once seen`() {
        val prev = senderState(ThreadSpamState.MIXED, spam = 1, replied = true)
        val out = decide(prev, isSpam = true, hasOutbound = false)
        assertTrue(out.newState.hasReplied)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    @Test fun `a reply brings an automatic SPAM sender back to the inbox`() {
        val prev = senderState(ThreadSpamState.SPAM, spam = 3)
        val out = decide(prev, isSpam = true, hasOutbound = true)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    // Row 4: a sender that has ever sent ham is never hidden

    @Test fun `CLEAN spam to MIXED SILENT`() {
        val out = decide(senderState(ThreadSpamState.CLEAN, ham = 2), isSpam = true)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    @Test fun `a sender with one ham stays MIXED however much spam follows`() {
        val out = replay(false, true, true, true, true, true, true, true, true, true)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
    }

    @Test fun `ham from a MIXED sender notifies and keeps it MIXED`() {
        val out = decide(senderState(ThreadSpamState.MIXED, spam = 1, ham = 1), isSpam = false)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    // Row 5: probation

    @Test fun `a new sender's first spam message stays in the inbox as SUSPECTED`() {
        val out = decide(null, isSpam = true)
        assertEquals(ThreadSpamState.SUSPECTED, out.newState.state)
        assertEquals(NotificationDecision.SILENT, out.notification)
        assertEquals(1, out.newState.spamCount)
    }

    // Row 6: only spam, two or more

    @Test fun `a second spam message with no ham moves the sender to SPAM`() {
        val out = replay(true, true)
        assertEquals(ThreadSpamState.SPAM, out.newState.state)
        assertEquals(NotificationDecision.NONE, out.notification)
    }

    // SPAM is not sticky against ham

    @Test fun `ham from an automatic SPAM sender brings it back to the inbox as MIXED`() {
        val out = decide(senderState(ThreadSpamState.SPAM, spam = 4), isSpam = false)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
        assertEquals(1, out.newState.hamCount)
    }

    @Test fun `ham from a SUSPECTED sender makes it MIXED`() {
        val out = decide(senderState(ThreadSpamState.SUSPECTED, spam = 1), isSpam = false)
        assertEquals(ThreadSpamState.MIXED, out.newState.state)
    }

    // Row 7: no spam

    @Test fun `new sender ham to CLEAN NORMAL`() {
        val out = decide(null, isSpam = false)
        assertEquals(ThreadSpamState.CLEAN, out.newState.state)
        assertEquals(NotificationDecision.NORMAL, out.notification)
    }

    @Test fun `CLEAN ham stays CLEAN`() {
        val out = decide(senderState(ThreadSpamState.CLEAN, ham = 1), isSpam = false)
        assertEquals(ThreadSpamState.CLEAN, out.newState.state)
        assertEquals(2, out.newState.hamCount)
    }

    // Order independence: the reason the ratio rule was dropped

    @Test fun `the outcome does not depend on arrival order`() {
        val otpFirst = replay(false, true, true, true)
        val promosFirst = replay(true, true, true, false)
        assertEquals(ThreadSpamState.MIXED, otpFirst.newState.state)
        assertEquals(otpFirst.newState.state, promosFirst.newState.state)
        assertEquals(otpFirst.newState.spamCount, promosFirst.newState.spamCount)
        assertEquals(otpFirst.newState.hamCount, promosFirst.newState.hamCount)
    }

    // deriveState

    @Test fun `deriveState follows the table`() {
        assertEquals(ThreadSpamState.CLEAN, ThreadSpamPolicy.deriveState(0, 0, false))
        assertEquals(ThreadSpamState.CLEAN, ThreadSpamPolicy.deriveState(0, 3, true))
        assertEquals(ThreadSpamState.SUSPECTED, ThreadSpamPolicy.deriveState(1, 0, false))
        assertEquals(ThreadSpamState.SPAM, ThreadSpamPolicy.deriveState(2, 0, false))
        assertEquals(ThreadSpamState.MIXED, ThreadSpamPolicy.deriveState(5, 1, false))
        assertEquals(ThreadSpamState.MIXED, ThreadSpamPolicy.deriveState(5, 0, true))
    }
}

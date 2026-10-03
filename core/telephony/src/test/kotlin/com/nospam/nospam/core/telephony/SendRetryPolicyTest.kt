// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.telephony.SmsManager
import com.nospam.nospam.core.telephony.SendRetryPolicy.Decision
import com.nospam.nospam.core.telephony.SendRetryPolicy.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The retry rules: which failures, how often, for how long. */
class SendRetryPolicyTest {

    @Test fun `only no service and radio off are retried`() {
        assertTrue(SendRetryPolicy.isTemporary(SmsManager.RESULT_ERROR_NO_SERVICE))
        assertTrue(SendRetryPolicy.isTemporary(SmsManager.RESULT_ERROR_RADIO_OFF))
        assertFalse(SendRetryPolicy.isTemporary(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        assertFalse(SendRetryPolicy.isTemporary(SmsManager.RESULT_ERROR_NULL_PDU))
    }

    @Test fun `the delay starts at five seconds, doubles and is capped at two hours`() {
        assertEquals(5_000L, SendRetryPolicy.delayFor(0))
        assertEquals(10_000L, SendRetryPolicy.delayFor(1))
        assertEquals(40_000L, SendRetryPolicy.delayFor(3))
        assertEquals(SendRetryPolicy.MAX_DELAY_MS, SendRetryPolicy.delayFor(20))
        assertEquals(SendRetryPolicy.MAX_DELAY_MS, SendRetryPolicy.delayFor(Int.MAX_VALUE))
    }

    @Test fun `a first temporary failure starts the window and waits five seconds`() {
        val decision = SendRetryPolicy.onTemporaryFailure(Entry(subscriptionId = 2, deliveryReport = false, lastSentAt = 900), now = 1_000)
        val entry = (decision as Decision.Retry).entry
        assertEquals(1_000L, entry.firstFailureAt)
        assertEquals(6_000L, entry.nextAt)
        assertEquals(2, entry.subscriptionId)
    }

    @Test fun `later failures keep the window's start and wait longer`() {
        val entry = Entry(null, false, firstFailureAt = 1_000, attempt = 2, lastSentAt = 30_000)
        val next = (SendRetryPolicy.onTemporaryFailure(entry, now = 31_000) as Decision.Retry).entry
        assertEquals(1_000L, next.firstFailureAt)
        assertEquals(31_000L + 20_000L, next.nextAt)
    }

    @Test fun `the message is given up twenty minutes after its first failure`() {
        val entry = Entry(null, false, firstFailureAt = 0, attempt = 5)
        val start = 1_000L
        val started = entry.copy(firstFailureAt = start)
        assertTrue(SendRetryPolicy.onTemporaryFailure(started, now = start + SendRetryPolicy.WINDOW_MS - 1) is Decision.Retry)
        assertEquals(Decision.GiveUp, SendRetryPolicy.onTemporaryFailure(started, now = start + SendRetryPolicy.WINDOW_MS))
    }

    @Test fun `a second part failing while the message already waits changes nothing`() {
        val waiting = Entry(null, false, firstFailureAt = 1_000, nextAt = 6_000)
        assertEquals(Decision.AlreadyWaiting, SendRetryPolicy.onTemporaryFailure(waiting, now = 1_001))
    }

    @Test fun `a message that never gets service is tried about eight times in its window`() {
        var entry = Entry(null, false)
        var now = 0L
        var sends = 1 // the original send
        while (true) {
            val decision = SendRetryPolicy.onTemporaryFailure(entry, now)
            if (decision !is Decision.Retry) break
            now = decision.entry.nextAt
            entry = decision.entry.copy(attempt = decision.entry.attempt + 1, lastSentAt = now, nextAt = 0)
            sends++
        }
        // 5 + 10 + 20 + 40 + 80 + 160 + 320 + 640 s passes the 20-minute window.
        assertEquals(9, sends)
    }

    @Test fun `a send with no result is stuck after five minutes`() {
        assertFalse(SendRetryPolicy.isStuck(lastSentAt = 0, now = 10 * 60_000))
        assertFalse(SendRetryPolicy.isStuck(lastSentAt = 1_000, now = 1_000 + SendRetryPolicy.STUCK_AFTER_MS - 1))
        assertTrue(SendRetryPolicy.isStuck(lastSentAt = 1_000, now = 1_000 + SendRetryPolicy.STUCK_AFTER_MS))
    }

    @Test fun `an entry survives being stored, with or without a SIM`() {
        val withSim = Entry(3, true, firstFailureAt = 10, attempt = 4, lastSentAt = 20, nextAt = 30)
        val noSim = Entry(null, false)
        assertEquals(withSim, Entry.decode(withSim.encode()))
        assertEquals(noSim, Entry.decode(noSim.encode()))
        assertEquals(null, Entry.decode("garbage"))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import com.nospam.nospam.core.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure delivery-report rules: TP-Status to provider status, how one part's
 * report changes a row's stored status, and provider status to
 * [DeliveryStatus]. Written from the spec independently of the implementation.
 */
class DeliveryRulesTest {
    private val none = -1
    private val complete = 0
    private val pending = 32
    private val failed = 64

    @Test fun `provider status constants match Android's`() {
        assertEquals(android.provider.Telephony.TextBasedSmsColumns.STATUS_NONE, none)
        assertEquals(android.provider.Telephony.TextBasedSmsColumns.STATUS_COMPLETE, complete)
        assertEquals(android.provider.Telephony.TextBasedSmsColumns.STATUS_PENDING, pending)
        assertEquals(android.provider.Telephony.TextBasedSmsColumns.STATUS_FAILED, failed)
    }

    // --- statusForReport ---

    @Test fun `TP-Status 0x00 to 0x1F is complete`() {
        for (tp in 0x00..0x1F) assertEquals("tp=$tp", complete, statusForReport(tp))
    }

    @Test fun `TP-Status 0x20 to 0x3F is pending`() {
        for (tp in 0x20..0x3F) assertEquals("tp=$tp", pending, statusForReport(tp))
    }

    @Test fun `TP-Status 0x40 and above is failed`() {
        for (tp in 0x40..0xFF) assertEquals("tp=$tp", failed, statusForReport(tp))
    }

    @Test fun `TP-Status boundaries`() {
        assertEquals(complete, statusForReport(0x1F))
        assertEquals(pending, statusForReport(0x20))
        assertEquals(pending, statusForReport(0x3F))
        assertEquals(failed, statusForReport(0x40))
        assertEquals(failed, statusForReport(0x41))
        assertEquals(failed, statusForReport(0x60))
    }

    // --- nextDeliveryStatus: every current x reported ---

    @Test fun `a failed row never changes`() {
        for (reported in listOf(complete, pending, failed)) {
            assertNull("reported=$reported", nextDeliveryStatus(failed, reported))
        }
    }

    @Test fun `reported failed marks any non-failed row failed`() {
        for (current in listOf(none, complete, pending)) {
            assertEquals("current=$current", failed, nextDeliveryStatus(current, failed))
        }
    }

    @Test fun `reported complete marks a none or pending row complete`() {
        assertEquals(complete, nextDeliveryStatus(none, complete))
        assertEquals(complete, nextDeliveryStatus(pending, complete))
    }

    @Test fun `reported complete on a complete row leaves it`() {
        assertNull(nextDeliveryStatus(complete, complete))
    }

    @Test fun `a late pending does not undo delivered`() {
        assertNull(nextDeliveryStatus(complete, pending))
    }

    @Test fun `reported pending on a none row marks it pending`() {
        assertEquals(pending, nextDeliveryStatus(none, pending))
    }

    @Test fun `reported pending on a pending row leaves it`() {
        assertNull(nextDeliveryStatus(pending, pending))
    }

    @Test fun `full current x reported table`() {
        val expected = mapOf(
            (none to complete) to complete, (none to pending) to pending, (none to failed) to failed,
            (complete to complete) to null, (complete to pending) to null, (complete to failed) to failed,
            (pending to complete) to complete, (pending to pending) to null, (pending to failed) to failed,
            (failed to complete) to null, (failed to pending) to null, (failed to failed) to null,
        )
        for ((pair, want) in expected) {
            assertEquals("current=${pair.first} reported=${pair.second}", want, nextDeliveryStatus(pair.first, pair.second))
        }
    }

    // --- deliveryStatusOf ---

    @Test fun `negative provider status is NONE`() {
        assertEquals(DeliveryStatus.NONE, deliveryStatusOf(-1))
        assertEquals(DeliveryStatus.NONE, deliveryStatusOf(-2))
        assertEquals(DeliveryStatus.NONE, deliveryStatusOf(Int.MIN_VALUE))
    }

    @Test fun `0 to 31 is DELIVERED`() {
        for (s in 0..31) assertEquals("s=$s", DeliveryStatus.DELIVERED, deliveryStatusOf(s))
    }

    @Test fun `32 to 63 is PENDING`() {
        for (s in 32..63) assertEquals("s=$s", DeliveryStatus.PENDING, deliveryStatusOf(s))
    }

    @Test fun `64 and above is FAILED`() {
        for (s in 64..255) assertEquals("s=$s", DeliveryStatus.FAILED, deliveryStatusOf(s))
        assertEquals(DeliveryStatus.FAILED, deliveryStatusOf(Int.MAX_VALUE))
    }

    @Test fun `stored statuses round-trip to the right DeliveryStatus`() {
        assertEquals(DeliveryStatus.NONE, deliveryStatusOf(none))
        assertEquals(DeliveryStatus.DELIVERED, deliveryStatusOf(statusForReport(0x00)))
        assertEquals(DeliveryStatus.PENDING, deliveryStatusOf(statusForReport(0x20)))
        assertEquals(DeliveryStatus.FAILED, deliveryStatusOf(statusForReport(0x41)))
    }
}

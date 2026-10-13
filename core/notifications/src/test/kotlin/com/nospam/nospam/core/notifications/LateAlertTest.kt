// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** [lateAlert]: a late verdict announces only a message that announced nothing, and only soon after it arrived. */
class LateAlertTest {

    @Test fun `a silent message that turns out real is announced`() {
        assertEquals(IncomingAlert.NOTIFY, lateAlert(IncomingAlert.NONE, IncomingAlert.NOTIFY, elapsedMillis = 5_000))
        // On screen by now: the in-app sound, as for any message there.
        assertEquals(IncomingAlert.IN_APP_SOUND, lateAlert(IncomingAlert.NONE, IncomingAlert.IN_APP_SOUND, elapsedMillis = 5_000))
    }

    @Test fun `a silent message that turns out spam stays silent`() {
        assertEquals(IncomingAlert.NONE, lateAlert(IncomingAlert.NONE, IncomingAlert.NONE, elapsedMillis = 5_000))
    }

    @Test fun `a message already announced is not announced again`() {
        for (atArrival in listOf(IncomingAlert.NOTIFY, IncomingAlert.NOTIFY_QUIET, IncomingAlert.IN_APP_SOUND)) {
            for (late in IncomingAlert.entries) {
                assertEquals("$atArrival then $late", IncomingAlert.NONE, lateAlert(atArrival, late, elapsedMillis = 5_000))
            }
        }
    }

    @Test fun `a verdict past the window announces nothing`() {
        assertEquals(IncomingAlert.NOTIFY, lateAlert(IncomingAlert.NONE, IncomingAlert.NOTIFY, LATE_ALERT_WINDOW_MS))
        assertEquals(IncomingAlert.NONE, lateAlert(IncomingAlert.NONE, IncomingAlert.NOTIFY, LATE_ALERT_WINDOW_MS + 1))
    }
}

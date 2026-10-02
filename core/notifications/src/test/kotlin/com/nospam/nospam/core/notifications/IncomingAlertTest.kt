// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import com.nospam.nospam.core.model.NotificationDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive table test for [incomingAlert], written from the function's
 * documented contract (CLAUDE.md / the task spec) independently of
 * MessageSounds.kt: a decision other than NORMAL never notifies or sounds;
 * a NORMAL decision for a conversation that is not on screen posts the usual
 * notification; a NORMAL decision for the conversation on screen never posts
 * a notification, only the in-app sound, and only when sounds are enabled.
 */
class IncomingAlertTest {

    private data class Case(
        val label: String,
        val decision: NotificationDecision,
        val threadId: Long,
        val visibleThreadId: Long?,
        val soundsEnabled: Boolean,
        val expected: IncomingAlert,
    )

    private val cases = buildList {
        for (threadId in listOf(5L, 100L)) {
            for (decision in NotificationDecision.entries) {
                for (visible in listOf(null, threadId, threadId + 1)) {
                    for (soundsEnabled in listOf(true, false)) {
                        val expected = when {
                            decision != NotificationDecision.NORMAL -> IncomingAlert.NONE
                            visible == null || visible != threadId -> IncomingAlert.NOTIFY
                            soundsEnabled -> IncomingAlert.IN_APP_SOUND
                            else -> IncomingAlert.NONE
                        }
                        add(
                            Case(
                                label = "decision=$decision threadId=$threadId visible=$visible sounds=$soundsEnabled",
                                decision = decision,
                                threadId = threadId,
                                visibleThreadId = visible,
                                soundsEnabled = soundsEnabled,
                                expected = expected,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun `exhaustive decision table`() {
        for (case in cases) {
            val actual = incomingAlert(
                decision = case.decision,
                threadId = case.threadId,
                visibleThreadId = case.visibleThreadId,
                soundsEnabled = case.soundsEnabled,
            )
            assertEquals(case.label, case.expected, actual)
        }
    }

    // A few named spot checks of the table's four corners, in case the
    // exhaustive loop above is ever weakened.

    @Test fun `SILENT never notifies or sounds even when the conversation is on screen with sounds on`() {
        assertEquals(
            IncomingAlert.NONE,
            incomingAlert(NotificationDecision.SILENT, threadId = 1L, visibleThreadId = 1L, soundsEnabled = true),
        )
    }

    @Test fun `NONE decision never notifies or sounds`() {
        assertEquals(
            IncomingAlert.NONE,
            incomingAlert(NotificationDecision.NONE, threadId = 1L, visibleThreadId = null, soundsEnabled = true),
        )
    }

    @Test fun `NORMAL for a different conversation always notifies regardless of sounds setting`() {
        assertEquals(
            IncomingAlert.NOTIFY,
            incomingAlert(NotificationDecision.NORMAL, threadId = 1L, visibleThreadId = 2L, soundsEnabled = false),
        )
        assertEquals(
            IncomingAlert.NOTIFY,
            incomingAlert(NotificationDecision.NORMAL, threadId = 1L, visibleThreadId = 2L, soundsEnabled = true),
        )
    }

    @Test fun `NORMAL with no visible conversation notifies`() {
        assertEquals(
            IncomingAlert.NOTIFY,
            incomingAlert(NotificationDecision.NORMAL, threadId = 1L, visibleThreadId = null, soundsEnabled = true),
        )
    }

    @Test fun `NORMAL for the conversation on screen sounds only when sounds are enabled, never notifies`() {
        assertEquals(
            IncomingAlert.IN_APP_SOUND,
            incomingAlert(NotificationDecision.NORMAL, threadId = 1L, visibleThreadId = 1L, soundsEnabled = true),
        )
        assertEquals(
            IncomingAlert.NONE,
            incomingAlert(NotificationDecision.NORMAL, threadId = 1L, visibleThreadId = 1L, soundsEnabled = false),
        )
    }

    // Suspected spam that stays in the inbox, with the user's setting.

    @Test fun `SILENT with the suspected spam setting on posts a quiet notification`() {
        assertEquals(
            IncomingAlert.NOTIFY_QUIET,
            incomingAlert(NotificationDecision.SILENT, threadId = 1L, visibleThreadId = null, soundsEnabled = true, notifySuspectedSpam = true),
        )
        assertEquals(
            IncomingAlert.NOTIFY_QUIET,
            incomingAlert(NotificationDecision.SILENT, threadId = 1L, visibleThreadId = 2L, soundsEnabled = false, notifySuspectedSpam = true),
        )
    }

    @Test fun `SILENT with the setting on does nothing for the conversation on screen`() {
        assertEquals(
            IncomingAlert.NONE,
            incomingAlert(NotificationDecision.SILENT, threadId = 1L, visibleThreadId = 1L, soundsEnabled = true, notifySuspectedSpam = true),
        )
    }

    @Test fun `the setting never makes a message routed to Spam announce itself`() {
        assertEquals(
            IncomingAlert.NONE,
            incomingAlert(NotificationDecision.NONE, threadId = 1L, visibleThreadId = null, soundsEnabled = true, notifySuspectedSpam = true),
        )
    }

    @Test fun `the setting does not change how a normal message announces itself`() {
        for (visible in listOf(null, 1L, 2L)) for (sounds in listOf(true, false)) {
            assertEquals(
                incomingAlert(NotificationDecision.NORMAL, 1L, visible, sounds, notifySuspectedSpam = false),
                incomingAlert(NotificationDecision.NORMAL, 1L, visible, sounds, notifySuspectedSpam = true),
            )
        }
    }
}

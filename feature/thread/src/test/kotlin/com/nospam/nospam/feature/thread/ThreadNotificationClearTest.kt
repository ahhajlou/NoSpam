// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The conversation on screen has no notification. A message that arrives while
 * the app is in the background with the conversation open is shown when the
 * user returns, so its notification goes then too, as in Google Messages.
 * Until 2026-10-13 only opening the conversation cleared it, and it stayed
 * until swiped (reproduced on the API 37 emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ThreadNotificationClearTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val threadId = 9L

    @Before fun setUp() {
        manager.createNotificationChannel(NotificationChannel("test", "test", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Posted as core:notifications does: the id is the thread id's hashCode. */
    private fun postFor(id: Long) {
        val n = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.sym_def_app_icon).build()
        manager.notify(id.hashCode(), n)
    }

    /** A conversation's notification in the messages group, and the group's summary. */
    private fun postGroupedFor(id: Long) {
        val n = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setGroup("incoming_messages").build()
        manager.notify(id.hashCode(), n)
    }

    private fun postSummary() {
        val n = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setGroup("incoming_messages").setGroupSummary(true).build()
        manager.notify("messages_summary", 0, n)
    }

    private fun activeIds() = manager.activeNotifications.map { it.id }

    private fun showConversation() {
        val viewModel = ThreadViewModel(FakeTelephonyDataSource().apply { nextThreadId = threadId })
        rule.setContent { ThreadScreen(threadId = threadId, viewModel = viewModel) }
        rule.waitForIdle()
    }

    @Test fun `opening the conversation clears its notification`() {
        postFor(threadId)
        showConversation()
        assertTrue(activeIds().isEmpty())
    }

    @Test fun `returning to the open conversation clears a notification that arrived meanwhile`() {
        showConversation()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        postFor(threadId)
        assertEquals("it stays while the app is away", listOf(threadId.hashCode()), activeIds())

        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertTrue(activeIds().isEmpty())
    }

    @Test fun `another conversation's notification is left alone`() {
        postFor(threadId + 1)
        showConversation()
        assertEquals(listOf((threadId + 1).hashCode()), activeIds())
    }

    @Test fun `the id matches the notification's for thread ids beyond Int range`() {
        val big = 3_000_000_000L
        postFor(big)
        clearThreadNotification(context, big)
        assertTrue(activeIds().isEmpty())
    }

    @Test fun `the group summary stays while another conversation is notified`() {
        postGroupedFor(threadId)
        postGroupedFor(threadId + 1)
        postSummary()
        clearThreadNotification(context, threadId)
        assertEquals(listOf("messages_summary", null), manager.activeNotifications.map { it.tag }.sortedBy { it == null })
    }

    @Test fun `the group summary goes with the last conversation`() {
        postGroupedFor(threadId)
        postSummary()
        clearThreadNotification(context, threadId)
        assertTrue(activeIds().isEmpty())
    }
}

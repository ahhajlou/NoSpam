// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ThreadScreen's `onVisibilityChange(threadId, visible)` callback:
 * it reports the screen visible while resumed and composed, and not visible
 * once it leaves composition. With `createComposeRule` the host activity is
 * already resumed, so this exercises the composition edge of the contract.
 * Written from the task spec independently of ThreadScreen.kt.
 *
 * See CLAUDE.md §9 for why Compose UI tests run through Robolectric and why
 * the phone-sized qualifiers are required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ThreadVisibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun fake(threadId: Long) = FakeTelephonyDataSource().apply { nextThreadId = threadId }

    private class ToggleHolder {
        var setter: ((Boolean) -> Unit)? = null
    }

    @Test fun `composing the screen reports it visible`() {
        val events = mutableListOf<Pair<Long, Boolean>>()
        rule.setContent {
            ThreadScreen(
                threadId = 9L,
                onVisibilityChange = { id, visible -> events.add(id to visible) },
                viewModel = ThreadViewModel(fake(9L)),
            )
        }
        rule.waitForIdle()

        assertTrue(events.contains(9L to true))
        assertTrue(events.none { it.first == 9L && !it.second })
    }

    @Test fun `composing the screen for a different thread id reports that id visible`() {
        val events = mutableListOf<Pair<Long, Boolean>>()
        rule.setContent {
            ThreadScreen(
                threadId = 77L,
                onVisibilityChange = { id, visible -> events.add(id to visible) },
                viewModel = ThreadViewModel(fake(77L)),
            )
        }
        rule.waitForIdle()

        assertTrue(events.contains(77L to true))
    }

    @Test fun `removing the screen from composition reports it not visible`() {
        val events = mutableListOf<Pair<Long, Boolean>>()
        val toggle = ToggleHolder()
        rule.setContent {
            var showState by remember { mutableStateOf(true) }
            toggle.setter = { showState = it }
            if (showState) {
                ThreadScreen(
                    threadId = 9L,
                    onVisibilityChange = { id, visible -> events.add(id to visible) },
                    viewModel = ThreadViewModel(fake(9L)),
                )
            }
        }
        rule.waitForIdle()
        assertTrue(events.contains(9L to true))

        rule.runOnUiThread { toggle.setter?.invoke(false) }
        rule.waitForIdle()

        assertEquals(9L to false, events.last())
    }

    @Test fun `toggling back into composition reports it visible again`() {
        val events = mutableListOf<Pair<Long, Boolean>>()
        val toggle = ToggleHolder()
        rule.setContent {
            var showState by remember { mutableStateOf(true) }
            toggle.setter = { showState = it }
            if (showState) {
                ThreadScreen(
                    threadId = 9L,
                    onVisibilityChange = { id, visible -> events.add(id to visible) },
                    viewModel = ThreadViewModel(fake(9L)),
                )
            }
        }
        rule.waitForIdle()

        rule.runOnUiThread { toggle.setter?.invoke(false) }
        rule.waitForIdle()
        rule.runOnUiThread { toggle.setter?.invoke(true) }
        rule.waitForIdle()

        assertEquals(9L to true, events.last())
        assertEquals(2, events.count { it == (9L to true) })
        assertEquals(1, events.count { it == (9L to false) })
    }
}

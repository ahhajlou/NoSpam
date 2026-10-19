// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen hands the compose box the ViewModel's text as of its last
 * recomposition, which during fast typing is a key behind what was typed. The
 * box must keep what was typed, and still take a change made elsewhere (the
 * box emptied after sending, a forwarded body).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ComposeBarTypingTest {
    @get:Rule val rule = createComposeRule()

    // The ViewModel's text, which onDraftChanged sets at once, and the text the
    // screen last recomposed with, which each test moves by hand.
    private var viewModelDraft = ""
    private var shown by mutableStateOf("")

    private fun composeBar() = rule.setContent {
        ComposeBar(
            draft = shown,
            currentDraft = { viewModelDraft },
            onDraftChanged = { viewModelDraft = it },
            onSend = {},
            sims = emptyList(),
            selectedSimId = null,
            onSimSelected = {},
        )
    }

    private fun text(): String =
        rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun `a key typed before the screen catches up is kept`() {
        composeBar()
        rule.onNode(hasSetTextAction()).performTextInput("a")
        rule.onNode(hasSetTextAction()).performTextInput("b")
        // The screen now recomposes with the text from one key earlier.
        shown = "a"
        rule.waitForIdle()
        assertEquals("ab", text())
        assertEquals("ab", viewModelDraft)
    }

    @Test fun `a change made elsewhere replaces the text`() {
        composeBar()
        rule.onNode(hasSetTextAction()).performTextInput("hello")
        shown = "hello"
        rule.waitForIdle()

        // Sent: the ViewModel empties the draft.
        viewModelDraft = ""
        shown = ""
        rule.waitForIdle()
        assertEquals("", text())

        // A forwarded body.
        viewModelDraft = "Meeting moved to 3pm"
        shown = "Meeting moved to 3pm"
        rule.waitForIdle()
        assertEquals("Meeting moved to 3pm", text())
    }
}

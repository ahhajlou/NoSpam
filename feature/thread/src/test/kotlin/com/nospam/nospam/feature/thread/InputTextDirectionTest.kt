// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What is typed is laid out in its own direction, not the layout's (CLAUDE.md
 * §7). Every case runs in a right-to-left layout, the one a Persian user has,
 * and reads the direction the text was actually laid out in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class InputTextDirectionTest {
    @get:Rule val rule = createComposeRule()

    private fun rtl(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }

    private fun composeBar() = rtl {
        var draft by remember { mutableStateOf("") }
        ComposeBar(
            draft = draft,
            onDraftChanged = { draft = it },
            onSend = {},
            sims = emptyList(),
            selectedSimId = null,
            onSimSelected = {},
        )
    }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun SemanticsNodeInteraction.directionAt(offset: Int = 0): ResolvedTextDirection {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first().getParagraphDirection(offset)
    }

    @Test fun `an English draft in a Persian layout reads left to right`() {
        composeBar()
        field().performTextInput("Meeting moved to 3pm.")
        assertEquals(ResolvedTextDirection.Ltr, field().directionAt())
    }

    @Test fun `a Persian draft reads right to left`() {
        composeBar()
        field().performTextInput("جلسه ساعت ۳ منتقل شد.")
        assertEquals(ResolvedTextDirection.Rtl, field().directionAt())
    }

    @Test fun `a mixed draft takes the direction of its first word`() {
        composeBar()
        field().performTextInput("Meeting در دفتر ساعت ۳")
        assertEquals(ResolvedTextDirection.Ltr, field().directionAt())
    }

    @Test fun `each line of a draft keeps its own direction`() {
        composeBar()
        val english = "See you at 3pm."
        field().performTextInput("$english\nباشه، می‌بینمت")
        assertEquals(ResolvedTextDirection.Ltr, field().directionAt(0))
        assertEquals(ResolvedTextDirection.Rtl, field().directionAt(english.length + 1))
    }

    @Test fun `a typed number in the recipient field reads left to right`() {
        rtl { NewConversationScreen() }
        field().performTextInput("+989121234567")
        assertEquals(ResolvedTextDirection.Ltr, field().directionAt())
    }

    @Test fun `a Persian name in the recipient field reads right to left`() {
        rtl { NewConversationScreen() }
        field().performTextInput("علی")
        assertEquals(ResolvedTextDirection.Rtl, field().directionAt())
    }
}

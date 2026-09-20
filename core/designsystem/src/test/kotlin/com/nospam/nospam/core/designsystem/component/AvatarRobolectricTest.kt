package com.nospam.nospam.core.designsystem.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** See CLAUDE.md §9 for why this runs through Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AvatarRobolectricTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `the avatar is decorative`() {
        // The row beside the avatar announces the name; the letter must not be
        // read a second time. Initial/color logic is unit-tested in ComponentLogicTest.
        rule.setContent { NoSpamTheme { Avatar(name = "Alice", colorKey = "alice") } }
        rule.onNodeWithText("A").assertDoesNotExist()
    }
}

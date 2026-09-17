package com.nospam.nospam.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AtomsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun avatar_is_decorative() {
        // The row beside the avatar announces the name; the letter must not be
        // read a second time. Initial/color logic is unit-tested in ComponentLogicTest.
        rule.setContent { NoSpamTheme { Avatar(name = "Alice", colorKey = "alice") } }
        rule.onNodeWithText("A").assertDoesNotExist()
    }

    @Test fun pill_chip_reports_click_and_selection() {
        var clicked = false
        rule.setContent {
            NoSpamTheme { PillChip(label = "All", selected = true, onClick = { clicked = true }) }
        }
        rule.onNodeWithText("All").assertIsDisplayed().assertIsSelected().performClick()
        assertTrue(clicked)
    }

    @Test fun search_placeholder_shows_text() {
        rule.setContent { NoSpamTheme { SearchBarPlaceholder(text = "Search conversations") } }
        rule.onNodeWithText("Search conversations").assertIsDisplayed()
    }
}

package com.example.nospam.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.nospam.core.designsystem.theme.NoSpamTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AtomsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun avatar_shows_initial() {
        rule.setContent { NoSpamTheme { Avatar(label = "Alice") } }
        rule.onNodeWithText("A").assertIsDisplayed()
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

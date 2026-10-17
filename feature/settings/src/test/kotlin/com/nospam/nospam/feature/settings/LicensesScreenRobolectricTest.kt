// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class LicensesScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    private val licenses = OpenSourceLicenses(
        rustCrates = listOf(LicensedComponent("cfg-if", "1.0.5", "MIT OR Apache-2.0", null, "Copyright (c) 2014 Alex Crichton")),
        androidLibraries = listOf(LicensedComponent("Activity", "1.10.1", "The Apache License, Version 2.0", "The Android Open Source Project", "Apache text")),
    )

    private fun waitFor(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun `lists hazm, the rust crates and the android libraries`() {
        rule.setContent { LicensesScreen(loadLicenses = { licenses }) }
        waitFor("cfg-if 1.0.5")
        rule.onNodeWithText("Spam filter data").assertIsDisplayed()
        rule.onNodeWithText("hazm").assertIsDisplayed()
        rule.onNodeWithText("Spam filter core (Rust crates)").assertIsDisplayed()
        rule.onNodeWithText("MIT OR Apache-2.0").assertIsDisplayed()
        rule.onNodeWithText("Android and Kotlin libraries").assertIsDisplayed()
        rule.onNodeWithText("Activity 1.10.1").assertIsDisplayed()
    }

    @Test fun `an entry opens its license text and its authors`() {
        rule.setContent { LicensesScreen(loadLicenses = { licenses }) }
        waitFor("Activity 1.10.1")
        rule.onNodeWithText("Activity 1.10.1").performClick()
        waitFor("Apache text")
        rule.onNodeWithText("Authors: The Android Open Source Project").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Apache text").assertCountEquals(0)
    }

    @Test fun `hazm opens its MIT notice`() {
        rule.setContent { LicensesScreen(loadLicenses = { licenses }) }
        rule.onNodeWithText("hazm").performClick()
        waitFor("Copyright (c) 2013 Alireza Nourian")
        rule.onNodeWithText("derived from hazm", substring = true).assertIsDisplayed()
    }
}

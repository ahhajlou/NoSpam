// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Onboarding on the JVM; see CLAUDE.md §9 for why Compose UI tests run through
 * Robolectric and why the phone-sized qualifiers are required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class OnboardingScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    private val application: android.app.Application
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun grantRequiredPermissions() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(*requiredPermissions().toTypedArray())
    }

    private fun holdSmsRole() {
        val roleManager = application.getSystemService(android.app.role.RoleManager::class.java)
        org.robolectric.Shadows.shadowOf(roleManager).addHeldRole(android.app.role.RoleManager.ROLE_SMS)
    }

    @Test fun `both setup steps say what they are for`() {
        rule.setContent { OnboardingScreen() }
        rule.onNodeWithText("Welcome to NoSpam").assertIsDisplayed()
        rule.onNodeWithText("Messages, contacts and SIM").assertIsDisplayed()
        rule.onNodeWithText("Default SMS app").assertIsDisplayed()
        // The reason each step exists, not just its name.
        rule.onNodeWithText("To read and send SMS", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Android only lets the default SMS app", substring = true).assertIsDisplayed()
    }

    @Test fun `the privacy promise is stated on the screen that asks for access`() {
        rule.setContent { OnboardingScreen() }
        rule.onNodeWithText("no internet permission", substring = true).assertIsDisplayed()
    }

    @Test fun `continue stays disabled while a step is outstanding`() {
        rule.setContent { OnboardingScreen() }
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test fun `granting the permissions marks that step done and offers no action`() {
        grantRequiredPermissions()
        rule.setContent { OnboardingScreen() }
        rule.onNodeWithText("Granted").assertIsDisplayed()
        rule.onNodeWithText("Grant SMS, Contacts & Phone permissions").assertDoesNotExist()
    }

    @Test fun `continue reports completion once both steps are done`() {
        grantRequiredPermissions()
        holdSmsRole()
        var completed = 0
        rule.setContent { OnboardingScreen(onComplete = { completed++ }) }
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, completed)
    }
}

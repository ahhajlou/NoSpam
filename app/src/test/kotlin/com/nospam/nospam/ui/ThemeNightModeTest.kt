// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.ui

import androidx.appcompat.app.AppCompatDelegate
import com.nospam.nospam.core.model.ThemeSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for `ThemeSetting.toNightMode()` written from the spec (SYSTEM ->
 * MODE_NIGHT_FOLLOW_SYSTEM, LIGHT -> MODE_NIGHT_NO, DARK -> MODE_NIGHT_YES),
 * not from the implementation. Plain JUnit: the mapping is compile-time
 * constants, nothing here needs Robolectric.
 */
class ThemeNightModeTest {

    @Test fun `SYSTEM maps to follow-system`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeSetting.SYSTEM.toNightMode())
    }

    @Test fun `LIGHT maps to night-no`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeSetting.LIGHT.toNightMode())
    }

    @Test fun `DARK maps to night-yes`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeSetting.DARK.toNightMode())
    }
}

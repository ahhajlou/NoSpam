// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates the app's own baseline profile.
 *
 * Release already carries the profiles AndroidX and Compose ship; this covers
 * NoSpam's own classes. A trace of a cold start showed ~750ms of main-thread
 * page faults arriving as many small reads, which is code and resource paging
 * that profile-guided layout is meant to reduce (see REVIEW.md section 1c).
 *
 * Run with an API 33+ emulator connected:
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
 *
 * Capture on an unrooted device needs API 33+, so this cannot run on the
 * SM-A730F (Android 9) the perf gates are measured on. Generate on the
 * emulator, then measure the resulting release build on the phone.
 */
class StartupBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndInbox() = rule.collect(packageName = PACKAGE) {
        pressHome()

        // Deliberately not startActivityAndWait(): it confirms the launch through
        // `dumpsys gfxinfo <pkg> framestats`, which a software-rendered emulator
        // leaves empty, so it fails with "Unable to confirm activity launch
        // completion []". Launching by shell and waiting on the UI records the
        // same code paths — a profile captures which code ran, not how long it
        // took, so losing the frame-accurate wait costs nothing here.
        device.executeShellCommand("am start -W -n $PACKAGE/$LAUNCH_ACTIVITY")
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT_MS)
        device.waitForIdle()

        // A fresh emulator has no SMS permission and is not the default SMS app,
        // so this may settle on onboarding rather than the inbox. Either way the
        // startup path worth profiling — Application, AppContainer, theme,
        // NavHost, first composition — has already run by this point.
        device.findObject(By.scrollable(true))?.let { list ->
            repeat(2) { list.fling(Direction.DOWN) }
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "com.nospam.nospam"
        const val LAUNCH_ACTIVITY = "com.nospam.nospam.MainActivity"
        const val TIMEOUT_MS = 15_000L
    }
}

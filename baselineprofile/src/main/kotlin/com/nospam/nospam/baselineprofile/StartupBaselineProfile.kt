package com.nospam.nospam.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
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
 * Run with the API 36 emulator connected:
 *     ./gradlew :app:generateBaselineProfile
 *
 * Capture on an unrooted device needs API 33+, so this cannot run on the
 * SM-A730F (Android 9) the perf gates are measured on. Generate on the
 * emulator, measure the resulting release build on the phone.
 */
class StartupBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndInbox() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()

        // The inbox is the screen the gates measure. Wait for real rows rather
        // than the skeleton placeholders, so the profile covers the list build
        // and not just the loading state.
        device.wait(Until.hasObject(By.textContains("Search conversations")), TIMEOUT_MS)
        device.waitForIdle()

        // A short scroll pulls in the row composables and the date formatting
        // path that the inbox actually exercises.
        device.findObject(By.scrollable(true))?.let { list ->
            repeat(2) { list.fling(androidx.test.uiautomator.Direction.DOWN) }
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "com.nospam.nospam"
        const val TIMEOUT_MS = 15_000L
    }
}

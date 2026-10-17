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
 * On an emulator, not a phone in use: the run installs its own build over the
 * app and uninstalls it afterwards, taking the app's data and the SMS role
 * with it. Uninstall a NoSpam signed with another key first: its install then
 * fails, the task still succeeds, and the profile file is left empty (check
 * `git diff --stat`). The emulator needs conversations to show, and a Play
 * Store image cannot write the SMS provider from the shell, so send them in as
 * real incoming messages, one of them from a phone number and containing
 * [SEEDED_SNIPPET], with the app installed as the default SMS app:
 *     adb -s emulator-5554 emu sms send +15552000001 "Meeting moved to 3pm"
 * They stay in the provider when the app is uninstalled.
 */
class StartupBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndInbox() = rule.collect(packageName = PACKAGE) {
        pressHome()

        // The install this runs against is fresh: no permissions, not the
        // default SMS app. Without these the launch settles on onboarding, and
        // the profile then covers none of the inbox: the previous one was
        // generated that way, and the inbox's first composition, the costliest
        // part of a cold start, ran interpreted.
        REQUIRED_PERMISSIONS.forEach { device.executeShellCommand("pm grant $PACKAGE android.permission.$it") }
        device.executeShellCommand("cmd role add-role-holder android.app.role.SMS $PACKAGE")

        // Deliberately not startActivityAndWait(): it confirms the launch through
        // `dumpsys gfxinfo <pkg> framestats`, which a software-rendered emulator
        // leaves empty, so it fails with "Unable to confirm activity launch
        // completion []". Launching by shell and waiting on the UI records the
        // same code paths — a profile captures which code ran, not how long it
        // took, so losing the frame-accurate wait costs nothing here.
        device.executeShellCommand("am start -W -n $PACKAGE/$LAUNCH_ACTIVITY")
        check(device.wait(Until.hasObject(By.text(INBOX_SEARCH_HINT)), TIMEOUT_MS)) {
            "The inbox never appeared; a profile captured now would not cover it"
        }
        device.waitForIdle()

        // Needs messages on the device, or there is no list to scroll or open:
        // see the class comment. Both are checked, as the inbox is: a run that
        // skipped them still wrote a profile, and on 2026-10-17 one had nothing
        // of the conversation screen in it: the filter chips scroll too, and a
        // fling meant for the list landed on them as a tap on "Known", which
        // hid the seeded row. The list is the tallest scrollable view, once its
        // rows have arrived, which is after the search bar.
        device.wait(Until.hasObject(By.textContains(SEEDED_SNIPPET)), TIMEOUT_MS)
        val list = checkNotNull(device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }) {
            "The inbox has no conversations to scroll; see StartupBaselineProfile's comment"
        }
        repeat(2) { list.fling(Direction.DOWN) }
        repeat(2) { list.fling(Direction.UP) }
        device.waitForIdle()
        val row = device.findObject(By.textContains(SEEDED_SNIPPET))
            ?: list.scrollUntil(Direction.DOWN, Until.findObject(By.textContains(SEEDED_SNIPPET)))
        checkNotNull(row) { "No conversation shows \"$SEEDED_SNIPPET\"; see StartupBaselineProfile's comment" }
        row.click()
        // A sender ID's conversation has no compose box, so this also needs the
        // message to come from a phone number.
        check(device.wait(Until.hasObject(By.text(COMPOSE_HINT)), TIMEOUT_MS)) {
            "The conversation with \"$SEEDED_SNIPPET\" opened without a compose box; send it from a phone number"
        }
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.text(INBOX_SEARCH_HINT)), TIMEOUT_MS)
    }

    private companion object {
        const val PACKAGE = "com.nospam.nospam"
        const val LAUNCH_ACTIVITY = "com.nospam.nospam.MainActivity"
        const val TIMEOUT_MS = 15_000L
        const val INBOX_SEARCH_HINT = "Search conversations"
        const val COMPOSE_HINT = "SMS message"
        const val SEEDED_SNIPPET = "Meeting moved"
        val REQUIRED_PERMISSIONS = listOf(
            "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
        )
    }
}

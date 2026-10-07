// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [launchIntentFor] rebuilds a request from its parsed values; reading that
 * intent back must give the same request, or a forwarded "send SMS to" would
 * open the wrong thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchIntentForTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun roundTrip(target: LaunchTarget) =
        launchIntentFor(context, MainActivity::class.java, target).toLaunchTarget()

    @Test fun `every kind of request reads back unchanged`() {
        val targets = listOf(
            LaunchTarget.Compose("+15551234", "hello"),
            LaunchTarget.Compose("+15551234", null),
            LaunchTarget.Compose("5000301630", "متن فارسی"),
            LaunchTarget.Compose("MCI", "a&b=c#d"),
            LaunchTarget.Thread(42L, "+15551234"),
            LaunchTarget.Thread(42L, null),
            LaunchTarget.Share("shared text"),
        )
        for (target in targets) assertEquals(target, roundTrip(target))
    }

    @Test fun `the intent is explicit, to the activity asked for`() {
        val intent = launchIntentFor(context, MainActivity::class.java, LaunchTarget.Compose("+1555", null))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
    }
}

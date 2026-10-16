// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's build of the core against training; see [FixtureParity]. Run before each
 * release: `./gradlew :core:ml:connectedDebugAndroidTest`. It installs only this
 * module's test APK, never the app.
 */
@RunWith(AndroidJUnit4::class)
class CoreParityDeviceTest {

    @Test fun phone_build_of_the_core_reproduces_training_on_every_fixture_case() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FixtureParity.check(
            fixtureJson = context.assets.open("spam_fixture.json").bufferedReader().use { it.readText() },
            classifier = TfidfSpamClassifier.fromAsset(context),
        )
    }
}

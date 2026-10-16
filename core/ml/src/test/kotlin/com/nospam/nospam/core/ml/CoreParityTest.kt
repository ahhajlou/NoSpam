// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The host build of the core (made by `cargoHostBuild`) against training; see [FixtureParity]. */
class CoreParityTest {

    @Test fun `host build of the core reproduces training on every fixture case`() = runTest {
        FixtureParity.check(
            fixtureJson = File("src/test/resources/spam_fixture.json").readText(),
            classifier = TfidfSpamClassifier.fromJson(File("src/main/assets/spam_model.json").readText()),
        )
    }
}

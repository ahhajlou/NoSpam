// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notice the About page shows is the one persian-sms-spam ships with the hazm rules
 * compiled into the Rust core, so it changes when they do. The resource keeps one line per
 * paragraph, so the texts are compared with whitespace collapsed.
 */
class HazmLicenseTest {

    private fun words(text: String) = text.trim().split(Regex("\\s+"))

    @Test fun `the shown hazm notice is the core's HAZM-LICENSE text`() {
        val core = File("../../third_party/persian-sms-spam/core/spam-core/rules/HAZM-LICENSE.txt").readText()
        val shown = File("src/main/res/raw/hazm_license.txt").readText()
        assertEquals(words(core.substring(core.indexOf("The MIT License (MIT)"))), words(shown))
    }
}

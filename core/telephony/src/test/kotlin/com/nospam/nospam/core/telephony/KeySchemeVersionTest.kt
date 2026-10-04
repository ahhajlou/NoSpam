// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key scheme names the libphonenumber version in the version catalog. A
 * library update can read some number differently; bumping the scheme with it
 * is what re-keys stored data (SenderKeyRepair), so an update cannot slip
 * through without one.
 */
class KeySchemeVersionTest {
    @Test fun `the key scheme names the libphonenumber version in use`() {
        val catalog = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle/libs.versions.toml") }
            .first { it.isFile }
        val version = Regex("""^libphonenumber\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(catalog.readText())!!.groupValues[1]
        assertTrue(
            "KEY_SCHEME ${PhoneNumberNormalizer.KEY_SCHEME} does not name libphonenumber $version: bump it",
            PhoneNumberNormalizer.KEY_SCHEME.startsWith("libphonenumber-$version/"),
        )
    }
}

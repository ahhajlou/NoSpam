// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The license data the build generates: the AboutLibraries plugin's list, which offline
 * has license texts only from app/aboutlibraries/licenses, and core:ml's crate list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenSourceLicensesLoaderTest {

    private val licenses by lazy { runBlocking { loadOpenSourceLicenses(ApplicationProvider.getApplicationContext()) } }

    @Test fun `every android library comes with a full license text`() {
        assertTrue("${licenses.androidLibraries.size} libraries", licenses.androidLibraries.size >= 100)
        val short = licenses.androidLibraries.filter { it.text.length < 500 }.map { it.name }
        assertTrue("libraries without a full license text: $short", short.isEmpty())
    }

    @Test fun `every rust crate comes with its own MIT text`() {
        assertTrue(licenses.rustCrates.any { it.name == "fancy-regex" })
        val missing = licenses.rustCrates.filterNot { "Permission is hereby granted, free of charge" in it.text }.map { it.name }
        assertTrue("crates without an MIT text: $missing", missing.isEmpty())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import android.content.Context
import com.nospam.nospam.R
import com.nospam.nospam.feature.settings.OpenSourceLicenses
import com.nospam.nospam.feature.settings.parseAndroidLibraries
import com.nospam.nospam.feature.settings.parseRustCrates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The licenses page's data, both generated at build time: the Android and Kotlin libraries
 * (AboutLibraries' plugin in this module, `R.raw.aboutlibraries`) and the crates in the
 * Rust core (core:ml's `rustLicenses` task). About 100 KB of JSON, read and parsed off the
 * main thread when the page opens.
 */
internal suspend fun loadOpenSourceLicenses(context: Context): OpenSourceLicenses = withContext(Dispatchers.IO) {
    fun raw(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    OpenSourceLicenses(
        rustCrates = parseRustCrates(raw(com.nospam.nospam.core.ml.R.raw.rust_licenses)),
        androidLibraries = parseAndroidLibraries(raw(R.raw.aboutlibraries)),
    )
}

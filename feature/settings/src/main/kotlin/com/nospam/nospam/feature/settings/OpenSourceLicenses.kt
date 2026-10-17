// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One component the app is built from, with the license text to show for it. */
data class LicensedComponent(
    val name: String,
    val version: String?,
    /** The license as its source states it, e.g. "MIT OR Apache-2.0". */
    val license: String,
    /** Who holds the copyright, when the license text does not name them. */
    val authors: String?,
    val text: String,
)

/** What the open-source licenses page lists, besides hazm's notice. */
data class OpenSourceLicenses(
    val rustCrates: List<LicensedComponent>,
    val androidLibraries: List<LicensedComponent>,
)

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private class AboutLibrariesFile(
    val libraries: List<Library> = emptyList(),
    val licenses: Map<String, License> = emptyMap(),
) {
    @Serializable
    class Library(
        val uniqueId: String,
        val name: String? = null,
        val artifactVersion: String? = null,
        val licenses: List<String> = emptyList(),
        val developers: List<Named> = emptyList(),
        val organization: Named? = null,
    )

    @Serializable
    class Named(val name: String? = null)

    @Serializable
    class License(val name: String? = null, val url: String? = null, val content: String? = null)
}

/**
 * The Android and Kotlin libraries, from `aboutlibraries.json`, which the AboutLibraries
 * Gradle plugin generates in :app. A license's text is shared by every library under it,
 * so the authors are listed separately.
 */
fun parseAndroidLibraries(text: String): List<LicensedComponent> {
    val file = json.decodeFromString<AboutLibrariesFile>(text)
    return file.libraries.map { library ->
        val licenses = library.licenses.map { id -> id to file.licenses[id] }
        LicensedComponent(
            name = library.name?.takeIf { it.isNotBlank() } ?: library.uniqueId,
            version = library.artifactVersion,
            license = licenses.joinToString(", ") { (id, license) -> license?.name ?: id },
            authors = library.developers.mapNotNull { it.name }.distinct().joinToString(", ")
                .ifBlank { library.organization?.name },
            text = licenses.joinToString("\n\n") { (id, license) ->
                license?.content?.takeIf { it.isNotBlank() } ?: license?.url ?: id
            },
        )
    }.sortedBy { it.name.lowercase() }
}

@Serializable
private class RustLicensesFile(val crates: List<Crate> = emptyList()) {
    @Serializable
    class Crate(val name: String, val version: String, val license: String, val text: String)
}

/**
 * The crates compiled into the spam filter's Rust core, from `rust_licenses.json`, which
 * core:ml's `rustLicenses` task generates. Each text is the crate's own license file, so
 * it names its copyright holders.
 */
fun parseRustCrates(text: String): List<LicensedComponent> =
    json.decodeFromString<RustLicensesFile>(text).crates.map {
        LicensedComponent(name = it.name, version = it.version, license = it.license, authors = null, text = it.text)
    }

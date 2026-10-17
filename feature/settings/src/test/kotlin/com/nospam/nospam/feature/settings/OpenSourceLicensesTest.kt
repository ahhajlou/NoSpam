// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSourceLicensesTest {

    @Test fun `android libraries take their license text and authors from aboutlibraries json`() {
        val json = """
            {"libraries": [
              {"uniqueId": "org.example:zeta", "name": "Zeta", "artifactVersion": "2.0", "licenses": ["MIT"],
               "developers": [{"name": "Zed"}], "organization": {"name": "Zeta Org"}, "description": "ignored"},
              {"uniqueId": "org.example:alpha", "name": "", "artifactVersion": "1.0", "licenses": ["Apache-2.0", "Unknown"],
               "developers": [], "organization": {"name": "Alpha Org"}}
            ],
             "licenses": {
               "MIT": {"name": "The MIT License", "url": "https://opensource.org/licenses/MIT", "content": "MIT text"},
               "Apache-2.0": {"name": "The Apache License, Version 2.0", "url": "https://apache.org", "content": "Apache text"}
             }}
        """.trimIndent()

        val libraries = parseAndroidLibraries(json)

        // Sorted by name; a blank name falls back to the coordinates.
        assertEquals(listOf("org.example:alpha", "Zeta"), libraries.map { it.name })
        val alpha = libraries[0]
        assertEquals("1.0", alpha.version)
        assertEquals("The Apache License, Version 2.0, Unknown", alpha.license)
        assertEquals("Alpha Org", alpha.authors) // no developers: the organization
        assertEquals("Apache text\n\nUnknown", alpha.text) // a license without data shows its id
        val zeta = libraries[1]
        assertEquals("Zed", zeta.authors)
        assertEquals("MIT text", zeta.text)
    }

    @Test fun `license text is reflowed into one line per paragraph for display`() {
        val file = "Copyright (c) 2014 Someone\n\nPermission is hereby granted, free of charge, to\n    any person obtaining a copy\n\n\nTHE SOFTWARE IS PROVIDED\n\"AS IS\"\n"
        assertEquals(
            "Copyright (c) 2014 Someone\n\nPermission is hereby granted, free of charge, to any person obtaining a copy\n\nTHE SOFTWARE IS PROVIDED \"AS IS\"",
            reflowParagraphs(file),
        )
    }

    @Test fun `rust crates carry their own license text`() {
        val json = """{"crates": [{"name": "cfg-if", "version": "1.0.5", "license": "MIT OR Apache-2.0",
            "repository": "https://github.com/rust-lang/cfg-if", "text": "Copyright (c) 2014 Alex Crichton\n\nPermission..."}]}"""

        val crate = parseRustCrates(json).single()

        assertEquals("cfg-if", crate.name)
        assertEquals("1.0.5", crate.version)
        assertEquals("MIT OR Apache-2.0", crate.license)
        assertNull(crate.authors)
        assertEquals("Copyright (c) 2014 Alex Crichton\n\nPermission...", crate.text)
    }
}

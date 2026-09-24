// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [DataStorePreferencesDataSource.exists], written from its KDoc in
 * PreferencesDataSource.kt -- "whether [file] has ever been written, checked
 * for the file on disk without reading it" -- against real, on-disk AndroidX
 * DataStores in a temporary folder. Written from the spec independently of
 * the implementation.
 */
class DataStoreExistsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun fileOf(file: PreferenceFile): File =
        File(tmp.root, "${file.fileName}.preferences_pb")

    private fun subject(
        fileExists: (PreferenceFile) -> Boolean = { fileOf(it).exists() },
    ): DataStorePreferencesDataSource {
        val stores = mutableMapOf<PreferenceFile, DataStore<Preferences>>()
        return DataStorePreferencesDataSource(
            stores = { file ->
                stores.getOrPut(file) {
                    PreferenceDataStoreFactory.create(
                        scope = scope,
                        produceFile = { fileOf(file) },
                    )
                }
            },
            fileExists = fileExists,
        )
    }

    @Test
    fun `a file never written does not exist`() {
        val subject = subject()
        assertFalse(subject.exists(PreferenceFile.SETTINGS))
    }

    @Test
    fun `a file exists once it has been edited`() = runBlocking {
        val subject = subject()
        subject.edit(PreferenceFile.SETTINGS) { it["flag"] = true }
        assertTrue(subject.exists(PreferenceFile.SETTINGS))
    }

    @Test
    fun `editing one file does not make another exist`() = runBlocking {
        val subject = subject()
        subject.edit(PreferenceFile.SETTINGS) { it["flag"] = true }
        assertFalse(subject.exists(PreferenceFile.DRAFTS))
        assertFalse(subject.exists(PreferenceFile.UI_SETTINGS))
    }

    @Test
    fun `a throwing on-disk check is treated as not existing`() {
        val subject = subject(fileExists = { throw RuntimeException("disk error") })
        assertFalse(subject.exists(PreferenceFile.SETTINGS))
    }
}

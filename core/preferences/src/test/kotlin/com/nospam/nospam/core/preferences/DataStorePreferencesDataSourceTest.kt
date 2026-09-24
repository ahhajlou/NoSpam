// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [DataStorePreferencesDataSource] against a real AndroidX DataStore
 * backed by files in a temporary folder. Written from the interface contract
 * in PreferencesDataSource.kt and the class's documented constructor, without
 * reading the implementation.
 */
class DataStorePreferencesDataSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var subject: DataStorePreferencesDataSource

    @Before
    fun setUp() {
        val stores = mutableMapOf<PreferenceFile, DataStore<Preferences>>()
        subject = DataStorePreferencesDataSource(stores = { file ->
            stores.getOrPut(file) {
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { File(tmp.root, "${file.fileName}.preferences_pb") },
                )
            }
        })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `data emits current contents and reflects edits`() = runBlocking {
        val before = firstEmission(PreferenceFile.SETTINGS)
        assertTrue(before.isEmpty())

        subject.edit(PreferenceFile.SETTINGS) { it["flag"] = true }

        val after = firstEmission(PreferenceFile.SETTINGS)
        assertEquals(true, after["flag"])
    }

    @Test
    fun `each supported type round trips with its kotlin type`() = runBlocking {
        subject.edit(PreferenceFile.SETTINGS) {
            it["b"] = true
            it["i"] = 7
            it["l"] = 7L
            it["f"] = 1.5f
            it["d"] = 2.5
            it["s"] = "hi"
        }

        val contents = firstEmission(PreferenceFile.SETTINGS)
        assertEquals(true, contents["b"])
        assertTrue(contents["i"] is Int)
        assertEquals(7, contents["i"])
        assertTrue(contents["l"] is Long)
        assertEquals(7L, contents["l"])
        assertTrue(contents["f"] is Float)
        assertEquals(1.5f, contents["f"])
        assertTrue(contents["d"] is Double)
        assertEquals(2.5, contents["d"])
        assertEquals("hi", contents["s"])
    }

    @Test
    fun `a key removed inside transform disappears, others are kept`() = runBlocking {
        subject.edit(PreferenceFile.SETTINGS) {
            it["keep"] = "yes"
            it["gone"] = "soon"
        }

        subject.edit(PreferenceFile.SETTINGS) { it.remove("gone") }

        val contents = firstEmission(PreferenceFile.SETTINGS)
        assertEquals("yes", contents["keep"])
        assertFalse(contents.containsKey("gone"))
    }

    @Test
    fun `writing a different type to an existing key replaces it`() = runBlocking {
        subject.edit(PreferenceFile.SETTINGS) { it["k"] = 1 }
        subject.edit(PreferenceFile.SETTINGS) { it["k"] = "one" }

        val contents = firstEmission(PreferenceFile.SETTINGS)
        assertEquals("one", contents["k"])
    }

    @Test
    fun `an unsupported value type throws and leaves the file unchanged`() = runBlocking {
        subject.edit(PreferenceFile.SETTINGS) { it["existing"] = "value" }

        try {
            subject.edit(PreferenceFile.SETTINGS) { it["bad"] = listOf(1, 2, 3) }
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        val contents = firstEmission(PreferenceFile.SETTINGS)
        assertEquals("value", contents["existing"])
        assertFalse(contents.containsKey("bad"))
    }

    @Test
    fun `edit returns the contents after the edit`() = runBlocking {
        val result = subject.edit(PreferenceFile.SETTINGS) {
            it["a"] = 1
            it["b"] = 2
        }

        assertEquals(1, result["a"])
        assertEquals(2, result["b"])
    }

    @Test
    fun `concurrent edits on one file are serialised`() = runBlocking {
        subject.edit(PreferenceFile.SETTINGS) { it["counter"] = 0 }

        val jobs = (1..50).map {
            async(Dispatchers.IO) {
                subject.edit(PreferenceFile.SETTINGS) { map ->
                    val current = map["counter"] as Int
                    map["counter"] = current + 1
                }
            }
        }
        jobs.awaitAll()

        val contents = withTimeout(5_000) { subject.data(PreferenceFile.SETTINGS).first() }
        assertEquals(50, contents["counter"])
    }

    @Test
    fun `files are independent`() = runBlocking {
        subject.edit(PreferenceFile.SETTINGS) { it["only_in_settings"] = "s" }
        subject.edit(PreferenceFile.DRAFTS) { it["only_in_drafts"] = "d" }

        val settings = firstEmission(PreferenceFile.SETTINGS)
        val drafts = firstEmission(PreferenceFile.DRAFTS)

        assertTrue(settings.containsKey("only_in_settings"))
        assertFalse(settings.containsKey("only_in_drafts"))
        assertTrue(drafts.containsKey("only_in_drafts"))
        assertFalse(drafts.containsKey("only_in_settings"))
    }

    private suspend fun firstEmission(file: PreferenceFile): Map<String, Any> =
        withTimeout(5_000) { subject.data(file).first() }
}

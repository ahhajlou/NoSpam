// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.testing

import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.preferences.PreferencesDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * In-memory preferences, one map per file. Edits are serialised like
 * DataStore's. [failReads] and [failWrites] make the file behave as if it
 * could not be read or written, for testing the callers' fallbacks.
 */
class FakePreferencesDataSource(
    initial: Map<PreferenceFile, Map<String, Any>> = emptyMap(),
) : PreferencesDataSource {
    private val files = PreferenceFile.entries.associateWith {
        MutableStateFlow(initial[it].orEmpty())
    }
    private val mutex = Mutex()
    private val written = mutableSetOf<PreferenceFile>()

    @Volatile var failReads = false
    @Volatile var failWrites = false

    /** Number of successful [edit] calls, across all files. */
    var editCount = 0
        private set

    override fun data(file: PreferenceFile): Flow<Map<String, Any>> = flow {
        if (failReads) throw IOException("fake read failure")
        emitAll(files.getValue(file))
    }

    override suspend fun edit(
        file: PreferenceFile,
        transform: (MutableMap<String, Any>) -> Unit,
    ): Map<String, Any> = mutex.withLock {
        if (failWrites) throw IOException("fake write failure")
        val state = files.getValue(file)
        val next = state.value.toMutableMap().also(transform).toMap()
        state.value = next
        written += file
        editCount++
        next
    }

    /** A file exists once it has been seeded with contents or edited. */
    override fun exists(file: PreferenceFile): Boolean =
        file in written || files.getValue(file).value.isNotEmpty()

    /** Current contents of [file], for assertions. */
    fun contents(file: PreferenceFile): Map<String, Any> = files.getValue(file).value
}

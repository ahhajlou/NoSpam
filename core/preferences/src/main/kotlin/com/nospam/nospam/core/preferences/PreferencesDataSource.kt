// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.preferences

import kotlinx.coroutines.flow.Flow

/**
 * One preferences file on disk. [fileName] is the DataStore file name that
 * installed apps already have, so it must never change: renaming one silently
 * resets every value in it.
 */
enum class PreferenceFile(val fileName: String) {
    /** App settings plus the backfill flag and the install id. */
    SETTINGS("settings"),

    /** Unsent drafts, one `draft_<threadId>` key per thread. */
    DRAFTS("drafts"),

    /** How the app looks: theme, dynamic color. */
    UI_SETTINGS("ui_settings"),
}

/**
 * Untyped key-value storage, one map per [PreferenceFile]. Keys are names,
 * values are `Boolean`, `Int`, `Long`, `Float`, `Double` or `String`.
 *
 * Deliberately knows nothing about what a key means: names, defaults and
 * failure policy live in core:data's repositories, which is what makes them
 * testable against a fake of this interface.
 */
interface PreferencesDataSource {
    /**
     * The file's contents, emitted once on collection and again after every
     * change. The flow fails with an `IOException` when the file cannot be read.
     */
    fun data(file: PreferenceFile): Flow<Map<String, Any>>

    /**
     * Applies [transform] to a copy of the file's contents and stores the
     * result: a key removed from the map is removed from the file. Edits to one
     * file are serialised, so a read-then-write inside [transform] is atomic.
     *
     * @return the contents after the edit.
     * @throws IllegalArgumentException if [transform] stores an unsupported type.
     */
    suspend fun edit(file: PreferenceFile, transform: (MutableMap<String, Any>) -> Unit): Map<String, Any>

    /**
     * Whether [file] has ever been written. Checks for the file on disk without
     * reading it, so it is cheap enough to call on the main thread before
     * deciding whether a blocking read is worth doing.
     */
    fun exists(file: PreferenceFile): Boolean
}

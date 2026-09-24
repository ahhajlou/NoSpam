// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.util.Log
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.preferences.PreferencesDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Unsent message drafts, one per thread.
 *
 * Lives in core:data rather than feature:thread because the inbox needs to
 * read drafts too (TODO.md "Drafts in the inbox"), and one feature module
 * never depends on another. Storage errors never reach the caller: a failed
 * read is "no draft", a failed write is logged and dropped.
 */
class DraftRepository(private val prefs: PreferencesDataSource) {

    /** The saved draft for [threadId], or null when there is none. */
    suspend fun load(threadId: Long): String? = try {
        prefs.data(PreferenceFile.DRAFTS).first()[key(threadId)] as? String
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Saves [text] as the draft for [threadId]; blank text removes the draft. */
    suspend fun save(threadId: Long, text: String) {
        try {
            prefs.edit(PreferenceFile.DRAFTS) {
                if (text.isBlank()) it.remove(key(threadId)) else it[key(threadId)] = text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Draft write failed", e) }
        }
    }

    /** Every saved draft by thread id; empty when the file cannot be read. */
    fun observeAll(): Flow<Map<Long, String>> = prefs.data(PreferenceFile.DRAFTS)
        .map { all ->
            buildMap {
                for ((name, value) in all) {
                    val id = name.removePrefix(PREFIX).takeIf { name.startsWith(PREFIX) }?.toLongOrNull()
                    if (id != null && value is String) put(id, value)
                }
            }
        }
        .catch { emit(emptyMap()) }

    private fun key(threadId: Long) = "$PREFIX$threadId"

    private companion object {
        const val TAG = "DraftRepository"
        const val PREFIX = "draft_"
    }
}

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

/** An unsent draft and when it was last saved (epoch millis; 0 if saved before that was recorded). */
data class Draft(val text: String, val savedAt: Long)

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

    /**
     * Saves [text] as the draft for [threadId], with when it was saved, which
     * the inbox sorts a drafted conversation by. Blank text removes the draft.
     */
    suspend fun save(threadId: Long, text: String, now: Long = System.currentTimeMillis()) {
        try {
            prefs.edit(PreferenceFile.DRAFTS) {
                if (text.isBlank()) {
                    it.remove(key(threadId))
                    it.remove(savedAtKey(threadId))
                } else {
                    it[key(threadId)] = text
                    it[savedAtKey(threadId)] = now
                }
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

    /** Every saved draft with when it was saved; empty when the file cannot be read. */
    fun observeDrafts(): Flow<Map<Long, Draft>> = prefs.data(PreferenceFile.DRAFTS)
        .map { all ->
            buildMap {
                for ((name, value) in all) {
                    val id = name.removePrefix(PREFIX).takeIf { name.startsWith(PREFIX) }?.toLongOrNull()
                    if (id != null && value is String) put(id, Draft(value, all[savedAtKey(id)] as? Long ?: 0L))
                }
            }
        }
        .catch { emit(emptyMap()) }

    private fun key(threadId: Long) = "$PREFIX$threadId"

    // "draft_at_<id>": starts with PREFIX, but "at_<id>" is not a thread id,
    // so the draft readers skip it.
    private fun savedAtKey(threadId: Long) = "${PREFIX}at_$threadId"

    private companion object {
        const val TAG = "DraftRepository"
        const val PREFIX = "draft_"
    }
}

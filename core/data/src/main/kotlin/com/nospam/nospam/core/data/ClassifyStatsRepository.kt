// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.util.Log
import com.nospam.nospam.core.model.ClassifyStats
import com.nospam.nospam.core.preferences.PreferenceFile
import com.nospam.nospam.core.preferences.PreferencesDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** What happened to the spam check of an incoming SMS. */
enum class ClassifyEvent(internal val key: String) {
    CHECKED("checked"),
    LATE("late"),
    FAILED("failed"),
    LEAKED("leaked"),
}

/**
 * Temporary counters of incoming-SMS spam checks, in their own preferences
 * file so removing them deletes one file (TODO.md, "Late spam checks"). Each
 * count is written when it happens. Storage errors never reach the caller: a
 * failed read is zeros, a failed write is logged and dropped.
 */
class ClassifyStatsRepository(
    private val prefs: PreferencesDataSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val stats: Flow<ClassifyStats> = prefs.data(PreferenceFile.CLASSIFY_STATS)
        .map { all ->
            ClassifyStats(
                since = all[SINCE] as? Long,
                checked = all[ClassifyEvent.CHECKED.key] as? Long ?: 0,
                late = all[ClassifyEvent.LATE.key] as? Long ?: 0,
                failed = all[ClassifyEvent.FAILED.key] as? Long ?: 0,
                leaked = all[ClassifyEvent.LEAKED.key] as? Long ?: 0,
            )
        }
        .catch { emit(ClassifyStats()) }

    /** Adds one to [event]'s count; the first count after a reset also sets `since`. */
    suspend fun increment(event: ClassifyEvent) = write {
        it.putIfAbsent(SINCE, now())
        it[event.key] = (it[event.key] as? Long ?: 0L) + 1
    }

    suspend fun reset() = write { it.clear() }

    private suspend fun write(transform: (MutableMap<String, Any>) -> Unit) {
        try {
            prefs.edit(PreferenceFile.CLASSIFY_STATS, transform)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Classify stats write failed", e) }
        }
    }

    private companion object {
        const val TAG = "ClassifyStatsRepository"
        const val SINCE = "since"
    }
}

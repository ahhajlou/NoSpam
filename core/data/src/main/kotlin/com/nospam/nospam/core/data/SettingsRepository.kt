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
import java.util.UUID

/**
 * App settings and the few app-state flags that live beside them.
 *
 * Failure policy: storage errors never reach the caller. A read that fails
 * returns the default, and a write that fails is logged and dropped, so a
 * broken preferences file degrades to "defaults" rather than a crash. The
 * defaults are chosen so that degrading is safe: spam protection stays on.
 *
 * The keys are those `SpamPreferences` used before this class existed, so
 * installed apps keep their values.
 */
class SettingsRepository(private val prefs: PreferencesDataSource) {

    /** Whether incoming messages are classified. Defaults to on. */
    val spamProtection: Flow<Boolean> = prefs.data(PreferenceFile.SETTINGS)
        .map { it[KEY_SPAM_PROTECTION] as? Boolean ?: true }
        .catch { emit(true) }

    suspend fun isSpamProtectionEnabled(): Boolean = spamProtection.first()

    suspend fun setSpamProtection(enabled: Boolean) = write { it[KEY_SPAM_PROTECTION] = enabled }

    /**
     * One-shot backfill guard: set when SMS permission is granted so the
     * history scan runs (and resumes once if the process died mid-scan);
     * cleared when any scan reaches a terminal state. While it is false, cold
     * starts never scan, so ordinary launches add no overhead.
     */
    suspend fun isBackfillPending(): Boolean = try {
        prefs.data(PreferenceFile.SETTINGS).first()[KEY_BACKFILL_PENDING] as? Boolean ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun setBackfillPending(pending: Boolean) = write { it[KEY_BACKFILL_PENDING] = pending }

    /**
     * Random UUID minted on first use and kept for the life of the install.
     *
     * The debug export stamps exported lines with it, so exported corpora can
     * be grouped by origin without carrying a device identifier: it is
     * app-scoped, resets on uninstall, and correlates with nothing else.
     * Minting happens inside one edit, so concurrent first callers agree.
     * When storage fails, each call returns a fresh id rather than throwing.
     */
    suspend fun installId(): String = try {
        prefs.edit(PreferenceFile.SETTINGS) {
            if (it[KEY_INSTALL_ID] !is String) it[KEY_INSTALL_ID] = UUID.randomUUID().toString()
        }[KEY_INSTALL_ID] as String
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UUID.randomUUID().toString()
    }

    private suspend fun write(transform: (MutableMap<String, Any>) -> Unit) {
        try {
            prefs.edit(PreferenceFile.SETTINGS, transform)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Settings write failed", e) }
        }
    }

    private companion object {
        const val TAG = "SettingsRepository"
        const val KEY_SPAM_PROTECTION = "spam_protection_enabled"
        const val KEY_BACKFILL_PENDING = "history_backfill_pending"
        const val KEY_INSTALL_ID = "install_id"
    }
}

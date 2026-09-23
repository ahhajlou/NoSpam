// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.util.Log
import com.nospam.nospam.core.model.ThemeSetting
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

    /**
     * Whether the user has ever changed an appearance setting. Cheap enough for
     * the main thread: it lets app startup skip reading [theme] and
     * [dynamicColor] when both can only be their defaults.
     */
    fun hasAppearanceSettings(): Boolean = prefs.exists(PreferenceFile.UI_SETTINGS)

    /** Light, dark or the device's choice. Defaults to [ThemeSetting.SYSTEM]. */
    val theme: Flow<ThemeSetting> = prefs.data(PreferenceFile.UI_SETTINGS)
        .map { stored -> ThemeSetting.entries.firstOrNull { it.name == stored[KEY_THEME] } ?: ThemeSetting.SYSTEM }
        .catch { emit(ThemeSetting.SYSTEM) }

    suspend fun setTheme(theme: ThemeSetting) = write(PreferenceFile.UI_SETTINGS) { it[KEY_THEME] = theme.name }

    /**
     * Wallpaper-based colors (Material You) instead of the brand palette.
     * Defaults to off. Stored as chosen; whether the device supports it
     * (Android 12+) is the caller's concern.
     */
    val dynamicColor: Flow<Boolean> = prefs.data(PreferenceFile.UI_SETTINGS)
        .map { it[KEY_DYNAMIC_COLOR] as? Boolean ?: false }
        .catch { emit(false) }

    suspend fun setDynamicColor(enabled: Boolean) =
        write(PreferenceFile.UI_SETTINGS) { it[KEY_DYNAMIC_COLOR] = enabled }

    /**
     * Phone numbers the user entered for their SIMs, by subscription id: many
     * carriers do not put the number on the SIM, so Android cannot report it.
     * Empty when none were entered or the file cannot be read. Keyed by
     * subscription id, so a new SIM card starts without one.
     */
    val simNumbers: Flow<Map<Int, String>> = prefs.data(PreferenceFile.SIM_SETTINGS)
        .map { all ->
            buildMap {
                for ((key, value) in all) {
                    val id = key.removePrefix(SIM_PREFIX).removeSuffix(SIM_NUMBER_SUFFIX).toIntOrNull()
                    if (key.startsWith(SIM_PREFIX) && key.endsWith(SIM_NUMBER_SUFFIX) && id != null && value is String) {
                        put(id, value)
                    }
                }
            }
        }
        .catch { emit(emptyMap()) }

    /** Stores [number] (trimmed) for the SIM; null or blank removes it. */
    suspend fun setSimNumber(subscriptionId: Int, number: String?) = write(PreferenceFile.SIM_SETTINGS) {
        val key = "$SIM_PREFIX$subscriptionId$SIM_NUMBER_SUFFIX"
        val trimmed = number?.trim()
        if (trimmed.isNullOrEmpty()) it.remove(key) else it[key] = trimmed
    }

    private suspend fun write(
        file: PreferenceFile = PreferenceFile.SETTINGS,
        transform: (MutableMap<String, Any>) -> Unit,
    ) {
        try {
            prefs.edit(file, transform)
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
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val SIM_PREFIX = "sim_"
        const val SIM_NUMBER_SUFFIX = "_number"
    }
}

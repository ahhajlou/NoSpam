package com.nospam.nospam.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.spamDataStore by preferencesDataStore("settings")

object SpamPreferences {
    private val KEY = booleanPreferencesKey("spam_protection_enabled")
    private val KEY_BACKFILL_PENDING = booleanPreferencesKey("history_backfill_pending")

    fun flow(context: Context): Flow<Boolean> =
        context.spamDataStore.data.map { it[KEY] ?: true }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.spamDataStore.edit { it[KEY] = enabled }
    }

    suspend fun isEnabled(context: Context): Boolean {
        return try { context.spamDataStore.data.first()[KEY] ?: true } catch (_: Exception) { true }
    }

    /**
     * One-shot backfill guard: set to `true` when SMS permission is granted so the
     * history scan runs (and resumes once if the process died mid-scan); cleared by
     * the app when any scan reaches a terminal state. When `false`, cold starts
     * never scan, so ordinary launches add no overhead.
     */
    suspend fun setBackfillPending(context: Context, pending: Boolean) {
        runCatching { context.spamDataStore.edit { it[KEY_BACKFILL_PENDING] = pending } }
    }

    suspend fun isBackfillPending(context: Context): Boolean {
        return try {
            context.spamDataStore.data.first()[KEY_BACKFILL_PENDING] ?: false
        } catch (_: Exception) {
            false
        }
    }
}

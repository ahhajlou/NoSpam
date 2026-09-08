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

    fun flow(context: Context): Flow<Boolean> =
        context.spamDataStore.data.map { it[KEY] ?: true }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.spamDataStore.edit { it[KEY] = enabled }
    }

    suspend fun isEnabled(context: Context): Boolean {
        return try { context.spamDataStore.data.first()[KEY] ?: true } catch (_: Exception) { true }
    }
}

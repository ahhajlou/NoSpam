// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// One delegate per file for the whole process: DataStore throws if two
// instances ever open the same file, which is why these are the only ones.
private val Context.settingsDataStore by preferencesDataStore(PreferenceFile.SETTINGS.fileName)
private val Context.draftsDataStore by preferencesDataStore(PreferenceFile.DRAFTS.fileName)
private val Context.uiSettingsDataStore by preferencesDataStore(PreferenceFile.UI_SETTINGS.fileName)
private val Context.simSettingsDataStore by preferencesDataStore(PreferenceFile.SIM_SETTINGS.fileName)

private fun Context.dataStoreFor(file: PreferenceFile): DataStore<Preferences> = when (file) {
    PreferenceFile.SETTINGS -> settingsDataStore
    PreferenceFile.DRAFTS -> draftsDataStore
    PreferenceFile.UI_SETTINGS -> uiSettingsDataStore
    PreferenceFile.SIM_SETTINGS -> simSettingsDataStore
}

class DataStorePreferencesDataSource internal constructor(
    private val stores: (PreferenceFile) -> DataStore<Preferences>,
    private val fileExists: (PreferenceFile) -> Boolean = { false },
) : PreferencesDataSource {

    constructor(context: Context) : this(
        stores = context.applicationContext::dataStoreFor,
        fileExists = { context.applicationContext.preferencesDataStoreFile(it.fileName).exists() },
    )

    override fun exists(file: PreferenceFile): Boolean = runCatching { fileExists(file) }.getOrDefault(false)

    override fun data(file: PreferenceFile): Flow<Map<String, Any>> =
        stores(file).data.map { it.byName() }

    override suspend fun edit(
        file: PreferenceFile,
        transform: (MutableMap<String, Any>) -> Unit,
    ): Map<String, Any> = stores(file).edit { prefs ->
        val keys = prefs.asMap().keys.associateBy { it.name }
        val before = prefs.byName()
        val after = before.toMutableMap().also(transform)
        after.values.forEach(::requireSupported)
        for ((name, key) in keys) if (name !in after) prefs -= key
        for ((name, value) in after) {
            if (before[name] == value) continue
            // The stored type may differ from the new one; drop the old key first.
            keys[name]?.let { prefs -= it }
            prefs.put(name, value)
        }
    }.byName()

    private fun Preferences.byName(): Map<String, Any> = asMap().mapKeys { it.key.name }

    private fun requireSupported(value: Any) = require(
        value is Boolean || value is Int || value is Long || value is Float || value is Double || value is String
    ) { "Unsupported preference type: ${value::class.java.name}" }

    private fun MutablePreferences.put(name: String, value: Any) {
        when (value) {
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is Double -> this[doublePreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
        }
    }
}

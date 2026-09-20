// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.draftStore by preferencesDataStore("drafts")

object DraftStore {
    suspend fun load(context: Context, threadId: Long): String? {
        val key = stringPreferencesKey("draft_$threadId")
        return context.draftStore.data.map { it[key] }.first()
    }
    suspend fun save(context: Context, threadId: Long, draft: String) {
        val key = stringPreferencesKey("draft_$threadId")
        context.draftStore.edit { if (draft.isBlank()) it.remove(key) else it[key] = draft }
    }
}

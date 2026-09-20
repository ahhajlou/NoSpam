// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.ModelMetadataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteModelMetadataDao(
    private val helper: SqliteNoSpamOpenHelper
) : ModelMetadataDao {
    override suspend fun get(): ModelMetadataEntity? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("model_metadata", null, "id = 1", null, null, null, null).use { c ->
            if (c.moveToFirst()) {
                ModelMetadataEntity(
                    id = c.getInt(c.getColumnIndexOrThrow("id")),
                    version = c.getString(c.getColumnIndexOrThrow("version")),
                    threshold = c.getDouble(c.getColumnIndexOrThrow("threshold")),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt"))
                )
            } else null
        }
    }

    override suspend fun upsert(entity: ModelMetadataEntity) {
        withContext(Dispatchers.IO) {
            val values = android.content.ContentValues().apply {
                put("id", entity.id)
                put("version", entity.version)
                put("threshold", entity.threshold)
                put("updatedAt", entity.updatedAt)
            }
            helper.writableDatabase.insertWithOnConflict(
                "model_metadata", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }
}

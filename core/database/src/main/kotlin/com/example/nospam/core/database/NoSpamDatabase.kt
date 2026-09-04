package com.example.nospam.core.database

import com.example.nospam.core.database.dao.BlocklistDao
import com.example.nospam.core.database.dao.InMemoryBlocklistDao
import com.example.nospam.core.database.dao.InMemoryModelMetadataDao
import com.example.nospam.core.database.dao.InMemorySpamVerdictDao
import com.example.nospam.core.database.dao.ModelMetadataDao
import com.example.nospam.core.database.dao.SpamVerdictDao

class NoSpamDatabase(
    val blocklistDao: BlocklistDao = InMemoryBlocklistDao(),
    val spamVerdictDao: SpamVerdictDao = InMemorySpamVerdictDao(),
    val modelMetadataDao: ModelMetadataDao = InMemoryModelMetadataDao(),
) {
    companion object {
        fun inMemory(): NoSpamDatabase = NoSpamDatabase()
    }
}

package com.nospam.nospam.core.database

import com.nospam.nospam.core.database.dao.ArchivedDao
import com.nospam.nospam.core.database.dao.BlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryArchivedDao
import com.nospam.nospam.core.database.dao.InMemoryBlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryModelMetadataDao
import com.nospam.nospam.core.database.dao.InMemorySpamVerdictDao
import com.nospam.nospam.core.database.dao.ModelMetadataDao
import com.nospam.nospam.core.database.dao.SpamVerdictDao

class NoSpamDatabase(
    val blocklistDao: BlocklistDao = InMemoryBlocklistDao(),
    val spamVerdictDao: SpamVerdictDao = InMemorySpamVerdictDao(),
    val modelMetadataDao: ModelMetadataDao = InMemoryModelMetadataDao(),
    val archivedDao: ArchivedDao = InMemoryArchivedDao(),
) {
    companion object {
        fun inMemory(): NoSpamDatabase = NoSpamDatabase()
    }
}

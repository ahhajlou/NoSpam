// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import com.nospam.nospam.core.database.dao.ArchivedDao
import com.nospam.nospam.core.database.dao.BlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryArchivedDao
import com.nospam.nospam.core.database.dao.InMemoryBlocklistDao
import com.nospam.nospam.core.database.dao.InMemoryMessageVerdictDao
import com.nospam.nospam.core.database.dao.InMemoryModelMetadataDao
import com.nospam.nospam.core.database.dao.InMemoryMutedDao
import com.nospam.nospam.core.database.dao.InMemoryPinnedDao
import com.nospam.nospam.core.database.dao.InMemorySenderStateDao
import com.nospam.nospam.core.database.dao.InMemorySpamVerdictDao
import com.nospam.nospam.core.database.dao.InMemoryStarredDao
import com.nospam.nospam.core.database.dao.MessageVerdictDao
import com.nospam.nospam.core.database.dao.ModelMetadataDao
import com.nospam.nospam.core.database.dao.MutedDao
import com.nospam.nospam.core.database.dao.PinnedDao
import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.dao.SpamVerdictDao
import com.nospam.nospam.core.database.dao.StarredDao

class NoSpamDatabase(
    val blocklistDao: BlocklistDao = InMemoryBlocklistDao(),
    val spamVerdictDao: SpamVerdictDao = InMemorySpamVerdictDao(),
    val modelMetadataDao: ModelMetadataDao = InMemoryModelMetadataDao(),
    val archivedDao: ArchivedDao = InMemoryArchivedDao(),
    val messageVerdictDao: MessageVerdictDao = InMemoryMessageVerdictDao(),
    val senderStateDao: SenderStateDao = InMemorySenderStateDao(),
    val starredDao: StarredDao = InMemoryStarredDao(),
    val pinnedDao: PinnedDao = InMemoryPinnedDao(),
    val mutedDao: MutedDao = InMemoryMutedDao(),
) {
    companion object {
        fun inMemory(): NoSpamDatabase = NoSpamDatabase()

        fun persistent(context: android.content.Context): NoSpamDatabase {
            val helper = SqliteNoSpamOpenHelper(context.applicationContext)
            return NoSpamDatabase(
                blocklistDao = com.nospam.nospam.core.database.dao.SqliteBlocklistDao(helper),
                spamVerdictDao = com.nospam.nospam.core.database.dao.SqliteSpamVerdictDao(helper),
                modelMetadataDao = com.nospam.nospam.core.database.dao.SqliteModelMetadataDao(helper),
                archivedDao = com.nospam.nospam.core.database.dao.SqliteArchivedDao(helper),
                messageVerdictDao = com.nospam.nospam.core.database.dao.SqliteMessageVerdictDao(helper),
                senderStateDao = com.nospam.nospam.core.database.dao.SqliteSenderStateDao(helper),
                starredDao = com.nospam.nospam.core.database.dao.SqliteStarredDao(helper),
                pinnedDao = com.nospam.nospam.core.database.dao.SqlitePinnedDao(helper),
                mutedDao = com.nospam.nospam.core.database.dao.SqliteMutedDao(helper),
            )
        }
    }
}

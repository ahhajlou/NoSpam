// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.telephony.TelephonyDataSource

/**
 * Read-only corpus access for the debug export tool.
 *
 * `feature:export` used to reach into `core:telephony` and `core:database`
 * directly, which made it the only feature bypassing the repository layer
 * (CLAUDE.md §4). Everything it needs is two reads, so they live here instead.
 */
class ExportRepository(
    private val telephony: TelephonyDataSource,
    private val db: NoSpamDatabase,
) {
    /** Every SMS the provider will hand us, newest-first ordering left to the source. */
    suspend fun allMessages(): List<Message> = telephony.getAllMessages()

    /**
     * `"spam"` / `"ham"` for a message that has a stored verdict, `null` when it
     * has none. A user correction (`userLabel`) outranks the classifier verdict.
     */
    suspend fun labelFor(message: Message): String? {
        val entity = runCatching { db.messageVerdictDao.getByMessageId(message.id.value) }
            .getOrNull() ?: return null
        val isSpam = entity.userLabel ?: entity.isSpam
        return if (isSpam) "spam" else "ham"
    }
}

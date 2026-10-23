// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.telephony.TelephonyDataSource

/**
 * Read-only corpus access for the export tool, whose files are contributed to
 * the training set by other people as well as by the developer.
 *
 * `feature:export` used to reach into `core:telephony` and `core:database`
 * directly, which made it the only feature bypassing the repository layer
 * (CLAUDE.md §4). Everything it needs is two reads, so they live here instead.
 *
 * [mask] is the spam filter's anonymizer (the Rust core's first stage in the
 * app), which replaces passwords, codes, card, IBAN and account numbers,
 * national IDs, phone numbers and emails with tags such as `[CARD]`. Training
 * runs the same rules first, so masking here costs the model nothing.
 */
class ExportRepository(
    private val telephony: TelephonyDataSource,
    private val db: NoSpamDatabase,
    private val mask: (String) -> String,
) {
    /**
     * Every received SMS, oldest first, with its text masked. The user's own
     * messages are never exported: they are the most private and teach a spam
     * model nothing. Throws [MaskingFailedException] rather than return a text
     * unmasked.
     */
    suspend fun receivedMessages(): List<Message> =
        telephony.getInboxMessagesSince(0L).map { it.copy(body = maskOrFail(it.body)) }

    private fun maskOrFail(text: String): String =
        try {
            mask(text)
        } catch (e: Throwable) {
            // A missing native library is an Error, not an Exception.
            throw MaskingFailedException(e)
        }

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

/** The anonymizer could not run, so nothing was exported. */
class MaskingFailedException(cause: Throwable) : Exception("Masking failed: ${cause.message}", cause)

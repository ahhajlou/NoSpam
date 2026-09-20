// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import android.content.Context
import android.provider.BlockedNumberContract
import android.util.Log
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BlocklistRepository(
    private val db: NoSpamDatabase,
    private val context: Context? = null,
    // Shared with ingress and backfill so the read-decide-write on sender_state
    // in `unblock` cannot interleave with a concurrent inbound message.
    private val spamStateWriter: SpamStateWriter = SpamStateWriter(db.senderStateDao),
) {
    fun observe(): Flow<List<BlocklistEntity>> = db.blocklistDao.observeAll()
    suspend fun block(address: String, reason: String? = null) {
        val normalized = if (context != null) PhoneNumberNormalizer.normalize(context, address) else address.trim()
        db.blocklistDao.insert(BlocklistEntity(address = normalized, reason = reason))
        // Also insert original form for alphanumeric fallback lookup
        if (normalized != address.trim()) {
            // Ensure raw form also findable via direct match if caller forgot to normalize
        }
        if (context != null) {
            withContext(Dispatchers.IO) {
                try {
                    if (isDefaultSmsApp(context)) {
                        val values = android.content.ContentValues().apply {
                            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, normalized)
                        }
                        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
                    }
                } catch (e: Exception) {
                    Log.w("BlocklistRepo", "System block failed", e)
                }
            }
        }
    }
    suspend fun unblock(address: String) {
        val normalized = if (context != null) PhoneNumberNormalizer.normalize(context, address) else address.trim()
        db.blocklistDao.deleteByAddress(normalized)
        // Also try raw
        if (normalized != address.trim()) db.blocklistDao.deleteByAddress(address.trim())
        if (context != null) {
            withContext(Dispatchers.IO) {
                try {
                    if (isDefaultSmsApp(context)) {
                        val variants = PhoneNumberNormalizer.normalizedVariants(context, address)
                        for (v in variants) {
                            try {
                                context.contentResolver.delete(
                                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                                    "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                                    arrayOf(v)
                                )
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w("BlocklistRepo", "System unblock failed", e)
                }
            }
        }
        clearBlockedSenderState(normalized)
        if (normalized != address.trim()) clearBlockedSenderState(address.trim())
    }

    /**
     * Returns an unblocked sender to the inbox.
     *
     * Deleting the blocklist rows is not enough on its own. An inbound message
     * that arrives while a sender is blocked makes ingress write
     * `sender_state = BLOCKED`, and `ConversationsRepository` routes any
     * BLOCKED sender into Spam. `ThreadSpamPolicy` then returns BLOCKED
     * unchanged forever, so without this the conversation never comes back
     * however many normal messages arrive afterwards.
     *
     * The pre-block state is not retained anywhere, so it is rebuilt from the
     * counters: a sender with no spam history returns to CLEAN, one with spam
     * history returns to MIXED, which keeps it in the inbox while still
     * marking its spam messages. The block was a user override, so that flag
     * is cleared too. States other than BLOCKED are left alone.
     */
    private suspend fun clearBlockedSenderState(normalizedAddress: String) {
        spamStateWriter.withSpamStateLock {
            val current = db.senderStateDao.getByAddress(normalizedAddress) ?: return@withSpamStateLock
            if (current.state != ThreadSpamState.BLOCKED) return@withSpamStateLock
            db.senderStateDao.upsert(
                current.copy(
                    state = if (current.spamCount > 0) ThreadSpamState.MIXED else ThreadSpamState.CLEAN,
                    isUserOverride = false,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }
    suspend fun isBlocked(address: String): Boolean {
        // Check app DB via normalized and raw
        val normalized = if (context != null) PhoneNumberNormalizer.normalize(context, address) else address.trim()
        if (db.blocklistDao.findByAddress(normalized) != null) return true
        if (normalized != address.trim() && db.blocklistDao.findByAddress(address.trim()) != null) return true
        // System check when context available
        if (context != null) {
            return try {
                val variants = PhoneNumberNormalizer.normalizedVariants(context, address)
                variants.any { BlockedNumberContract.isBlocked(context, it) }
            } catch (_: Exception) { false }
        }
        return false
    }

    private fun isDefaultSmsApp(ctx: Context): Boolean =
        com.nospam.nospam.core.telephony.DefaultSmsApp.isHeld(ctx)
}

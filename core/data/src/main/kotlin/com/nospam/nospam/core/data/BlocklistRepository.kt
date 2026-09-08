package com.nospam.nospam.core.data

import android.content.Context
import android.provider.BlockedNumberContract
import android.util.Log
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BlocklistRepository(
    private val db: NoSpamDatabase,
    private val context: Context? = null,
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

    private fun isDefaultSmsApp(ctx: Context): Boolean {
        return try {
            val pkg = ctx.packageName
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                rm?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
            } else {
                @Suppress("DEPRECATION")
                android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) == pkg
            }
        } catch (_: Exception) { false }
    }
}

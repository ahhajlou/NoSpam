package com.example.nospam.core.data

import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.database.entity.BlocklistEntity
import kotlinx.coroutines.flow.Flow

class BlocklistRepository(private val db: NoSpamDatabase) {
    fun observe(): Flow<List<BlocklistEntity>> = db.blocklistDao.observeAll()
    suspend fun block(address: String, reason: String? = null) {
        db.blocklistDao.insert(BlocklistEntity(address = address, reason = reason))
    }
    suspend fun unblock(address: String) = db.blocklistDao.deleteByAddress(address)
    suspend fun isBlocked(address: String): Boolean = db.blocklistDao.findByAddress(address) != null
}

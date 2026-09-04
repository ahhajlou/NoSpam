package com.example.nospam.core.data

import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.database.entity.SpamVerdictEntity
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Message
import com.example.nospam.core.model.RawMessage
import com.example.nospam.core.model.SpamLabel
import com.example.nospam.core.model.SpamVerdict
import com.example.nospam.core.model.ThreadId
import com.example.nospam.core.ml.SpamClassifier
import com.example.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SmsIngressUseCaseTest {
    private class FakeTelephony(
        var threadId: Long = 7L,
        var insertResult: Long? = 99L,
    ) : TelephonyDataSource {
        val inserted = mutableListOf<Triple<String, String, Boolean>>()
        private val flow = MutableStateFlow<List<Conversation>>(emptyList())
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override suspend fun getConversations(): List<Conversation> = emptyList()
        override suspend fun getMessages(threadId: ThreadId): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> =
            Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long? {
            inserted.add(Triple(address, body, read))
            return insertResult
        }
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? = 2L
        override suspend fun getOrCreateThreadId(address: String): Long = threadId
    }

    private class FakeClassifier(private val isSpam: Boolean) : SpamClassifier {
        override suspend fun classify(message: RawMessage) =
            SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 2.0 else -2.0)
        override suspend fun classifyText(text: String) =
            SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 2.0 else -2.0)
    }

    @Test fun `spam is inserted as read and verdict stored`() = runTest {
        val telephony = FakeTelephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony, FakeClassifier(isSpam = true), db)

        val result = useCase.handle(RawMessage("+98912", "win prize now", 12345L))

        assertTrue(result.isSpam)
        assertEquals(ThreadId(7L), result.threadId)
        assertEquals(99L, result.messageId)
        // READ=1 suppresses heads-up for spam.
        assertEquals(listOf(Triple("+98912", "win prize now", true)), telephony.inserted)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertTrue(verdict.isSpam)
        assertFalse(verdict.isUserOverride)
    }

    @Test fun `ham is inserted as unread`() = runTest {
        val telephony = FakeTelephony()
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony, FakeClassifier(isSpam = false), db)

        val result = useCase.handle(RawMessage("+98912", "see you tomorrow", 12345L))

        assertFalse(result.isSpam)
        assertEquals(listOf(Triple("+98912", "see you tomorrow", false)), telephony.inserted)
        val verdict = db.spamVerdictDao.getByThread(7L)!!
        assertFalse(verdict.isSpam)
    }

    @Test fun `insert failure still returns verdict`() = runTest {
        val telephony = FakeTelephony(insertResult = null)
        val db = NoSpamDatabase.inMemory()
        val useCase = SmsIngressUseCase(telephony, FakeClassifier(isSpam = false), db)

        val result = useCase.handle(RawMessage("+98912", "hi", 1L))

        assertNull(result.messageId)
        assertNotNull(db.spamVerdictDao.getByThread(7L))
    }

    @Test fun `pruneOldSpam keeps user overrides`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = SpamRepository(db, FakeClassifier(isSpam = true))
        val old = System.currentTimeMillis() - 31L * 24L * 60L * 60L * 1000L
        db.spamVerdictDao.upsert(
            SpamVerdictEntity(threadId = 1L, isSpam = true, score = 1.0, isUserOverride = false, updatedAt = old)
        )
        db.spamVerdictDao.upsert(
            SpamVerdictEntity(threadId = 2L, isSpam = true, score = 1.0, isUserOverride = true, updatedAt = old)
        )
        assertEquals(1, repo.pruneOldSpam())
        assertNull(db.spamVerdictDao.getByThread(1L))
        assertNotNull(db.spamVerdictDao.getByThread(2L))
    }
}

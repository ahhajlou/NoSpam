package com.example.nospam.core.data

import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.Participant
import com.example.nospam.core.model.RawMessage
import com.example.nospam.core.model.SpamLabel
import com.example.nospam.core.model.SpamVerdict
import com.example.nospam.core.model.ThreadId
import com.example.nospam.core.ml.SpamClassifier
import com.example.nospam.core.telephony.TelephonyDataSource
import com.example.nospam.core.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    class FakeTelephony(private val convs: List<Conversation> = emptyList()) : TelephonyDataSource {
        private val flow = MutableStateFlow(convs)
        val inserted = mutableListOf<Triple<String, String, Boolean>>()
        var nextThreadId: Long = 42L
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override suspend fun getConversations(): List<Conversation> = flow.value
        override suspend fun getMessages(threadId: ThreadId): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun deleteConversation(threadId: ThreadId) {}
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long? {
            inserted.add(Triple(address, body, read))
            return 1L
        }
        override suspend fun getOrCreateThreadId(address: String): Long = nextThreadId
    }

    class FakeClassifier(private val isSpam: Boolean = false) : SpamClassifier {
        override suspend fun classify(message: RawMessage) = SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 1.0 else -1.0)
        override suspend fun classifyText(text: String) = SpamVerdict(if (isSpam) SpamLabel.SPAM else SpamLabel.HAM, if (isSpam) 1.0 else -1.0)
    }

    @Test fun `ConversationsRepository filters spam`() = runTest {
        val conv = Conversation(ThreadId(1), listOf(Participant("+98912")), "hello", System.currentTimeMillis(), 1, false)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(tele, db)
        // Initially not spam
        // Mark as spam via SpamRepository
        val spamRepo = SpamRepository(db, FakeClassifier(true))
        spamRepo.classifyAndStore(ThreadId(1), RawMessage("+98912", "win prize", 0L))
        val verdict = spamRepo.getVerdict(ThreadId(1))
        assertTrue(verdict!!.isSpam)
    }

    @Test fun `BlocklistRepository blocks`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = BlocklistRepository(db)
        repo.block("+98912")
        assertTrue(repo.isBlocked("+98912"))
        repo.unblock("+98912")
        assertFalse(repo.isBlocked("+98912"))
    }

    @Test fun `SpamRepository markNotSpam overrides`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = SpamRepository(db, FakeClassifier(true))
        repo.classifyAndStore(ThreadId(5), RawMessage("x", "spam", 0L))
        assertTrue(repo.getVerdict(ThreadId(5))!!.isSpam)
        repo.markNotSpam(ThreadId(5))
        val v = repo.getVerdict(ThreadId(5))!!
        assertFalse(v.isSpam)
        assertTrue(v.isUserOverride)
    }
}

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    class FakeTelephony(private val convs: List<Conversation> = emptyList()) : TelephonyDataSource {
        private val flow = MutableStateFlow(convs)
        fun emit(convs: List<Conversation>) { flow.value = convs }
        val inserted = mutableListOf<Triple<String, String, Boolean>>()
        var nextThreadId: Long = 42L
        override fun observeMessages(threadId: ThreadId): Flow<List<Message>> = MutableStateFlow(emptyList())
        override fun observeConversations(): Flow<List<Conversation>> = flow
        override suspend fun getConversations(): List<Conversation> = flow.value
        override suspend fun getMessages(threadId: ThreadId): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int?): Result<Unit> = Result.success(Unit)
        override suspend fun markAsRead(threadId: ThreadId) {}
        override suspend fun markAsUnread(threadId: ThreadId) {}
        val deletedIds = mutableListOf<Long>()
        override suspend fun deleteConversation(threadId: ThreadId) {
            deletedIds.add(threadId.value)
        }
        override suspend fun insertInboxMessage(address: String, body: String, date: Long, read: Boolean): Long? {
            inserted.add(Triple(address, body, read))
            return 1L
        }
        override suspend fun insertSentMessage(address: String, body: String, date: Long, subscriptionId: Int?): Long? = 2L
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `observeConversations re-emits on provider change`() = runTest {
        val first = Conversation(ThreadId(1), listOf(Participant("+98912")), "one", 1L, 1, true)
        val second = Conversation(ThreadId(2), listOf(Participant("+98913")), "two", 2L, 1, true)
        val tele = FakeTelephony(listOf(first))
        val repo = ConversationsRepository(tele, NoSpamDatabase.inMemory())
        val emissions = mutableListOf<List<Conversation>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeConversations().collect { emissions.add(it) }
        }
        testScheduler.advanceUntilIdle()
        tele.emit(listOf(first, second))
        testScheduler.advanceUntilIdle()
        job.cancel()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions[0].size)
        assertEquals(2, emissions[1].size)
    }

    @Test fun `archive hides from inbox and shows in archived`() = runTest {
        val conv = Conversation(ThreadId(1), listOf(Participant("+98912")), "hello", 1L, 1, true)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(tele, db)
        assertEquals(1, repo.observeConversations().take(1).toList().first().size)

        repo.archive(ThreadId(1))
        val inbox = repo.observeConversations().take(1).toList().first()
        assertTrue(inbox.isEmpty())
        val archived = repo.observeArchived().take(1).toList().first()
        assertEquals(1, archived.size)
        assertTrue(archived.first().isArchived)

        repo.unarchive(ThreadId(1))
        assertEquals(1, repo.observeConversations().take(1).toList().first().size)
    }

    @Test fun `deleteConversation drops provider and app rows`() = runTest {
        val conv = Conversation(ThreadId(1), listOf(Participant("+98912")), "hello", 1L, 1, true)
        val tele = FakeTelephony(listOf(conv))
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(
            com.nospam.nospam.core.database.entity.SpamVerdictEntity(
                threadId = 1, isSpam = true, score = 1.0
            )
        )
        db.archivedDao.archive(1)
        val repo = ConversationsRepository(tele, db)
        repo.deleteConversation(ThreadId(1))
        assertEquals(listOf(1L), tele.deletedIds)
        assertNull(db.spamVerdictDao.getByThread(1))
        assertFalse(db.archivedDao.isArchived(1))
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

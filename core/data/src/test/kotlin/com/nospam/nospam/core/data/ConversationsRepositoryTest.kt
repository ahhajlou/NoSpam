// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import app.cash.turbine.test
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConversationsRepository: inbox/spam/archived list membership, per core-data.md
 * and the split of the original RepositoryTest by scenario group (Wave 2A).
 */
class ConversationsRepositoryTest {
    // Mirrors PhoneNumberNormalizer behavior for tests (no Android Context).
    // "0"-prefixed local form -> E.164 +98..., everything else trim/uppercase.
    private val e164Normalizer: (String) -> String = { raw ->
        val t = raw.trim()
        if (t.startsWith("0") && t.length >= 10) "+98" + t.drop(1) else t.uppercase()
    }

    private fun conv(id: Long, address: String, snippet: String = "hello", date: Long = 1L, read: Boolean = true) =
        Conversation(ThreadId(id), listOf(Participant(address)), snippet, date, 1, read)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `legacy spam_verdict without sender state does not move conversation`() = runTest {
        val tele = FakeTelephonyDataSource(listOf(conv(1, "+98912", read = false)))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope)
        // Only a legacy threadId-keyed verdict exists, no SenderState.
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0))
        val inbox = repo.observeConversations().first()
        val spam = repo.observeSpam().first()
        // Legacy table must NOT drive list membership anymore (single source = sender_state).
        assertEquals(1, inbox.size)
        assertTrue(spam.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `observeConversations re-emits on provider change`() = runTest {
        val first = conv(1, "+98912", "one")
        val second = conv(2, "+98913", "two")
        val tele = FakeTelephonyDataSource(listOf(first))
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, NoSpamDatabase.inMemory(), testScope)
        repo.observeConversations().test {
            assertEquals(1, awaitItem().size)
            tele.emitConversations(listOf(first, second))
            assertEquals(2, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `refresh re-reads a provider that failed before the permission was granted`() = runTest {
        // Like RealTelephonyDataSource: the list is read when the flow is
        // collected, a denied read is empty, and a grant is not a provider change.
        var granted = false
        val stored = listOf(conv(1, "+98912"))
        val tele = object : TelephonyDataSource by FakeTelephonyDataSource() {
            override fun observeConversations(): Flow<List<Conversation>> =
                flow { emit(if (granted) stored else emptyList()) }
        }
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, NoSpamDatabase.inMemory(), testScope)
        repo.observeConversations().test {
            assertTrue(awaitItem().isEmpty())
            granted = true
            repo.refresh()
            assertEquals(stored.map { it.threadId }, awaitItem().map { it.threadId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `archive hides from inbox and shows in archived`() = runTest {
        val tele = FakeTelephonyDataSource(listOf(conv(1, "+98912")))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope)
        assertEquals(1, repo.observeConversations().first().size)

        repo.archive(ThreadId(1))
        val inbox = repo.observeConversations().first()
        assertTrue(inbox.isEmpty())
        val archived = repo.observeArchived().first()
        assertEquals(1, archived.size)
        assertTrue(archived.first().isArchived)

        repo.unarchive(ThreadId(1))
        assertEquals(1, repo.observeConversations().first().size)
    }

    @Test fun `deleteConversation drops provider and app rows`() = runTest {
        val tele = FakeTelephonyDataSource(listOf(conv(1, "+98912")))
        val db = NoSpamDatabase.inMemory()
        db.spamVerdictDao.upsert(SpamVerdictEntity(threadId = 1, isSpam = true, score = 1.0))
        db.archivedDao.archive(1)
        val repo = ConversationsRepository(tele, db)
        repo.deleteConversation(ThreadId(1))
        assertEquals(listOf(1L), tele.deletedThreadIds)
        assertNull(db.spamVerdictDao.getByThread(1))
        assertFalse(db.archivedDao.isArchived(1))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `spam membership single source by sender state`() = runTest {
        val states = listOf(
            Triple(1L, "09111111111", ThreadSpamState.CLEAN),
            Triple(2L, "09222222222", ThreadSpamState.MIXED),
            Triple(3L, "09333333333", ThreadSpamState.TRUSTED),
            Triple(4L, "09444444444", ThreadSpamState.SPAM),
            Triple(5L, "09555555555", ThreadSpamState.BLOCKED),
        )
        val convs = states.map { (id, addr, _) -> conv(id, addr) }
        val tele = FakeTelephonyDataSource(convs)
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope, e164Normalizer)
        states.forEach { (_, addr, state) ->
            db.senderStateDao.upsert(
                SenderStateEntity(normalizedAddress = e164Normalizer(addr), state = state, spamCount = 1, hamCount = 1)
            )
        }
        val inbox = repo.observeConversations().first()
        val spam = repo.observeSpam().first()
        assertEquals(setOf(1L, 2L, 3L), inbox.map { it.threadId.value }.toSet())
        assertEquals(setOf(4L, 5L), spam.map { it.threadId.value }.toSet())
        // The split bug: same conversation must never appear in both lists.
        assertTrue(inbox.map { it.threadId.value }.toSet().intersect(spam.map { it.threadId.value }.toSet()).isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `raw address still matched against E164 sender state`() = runTest {
        // Conversation participant is the raw local form (0912...), SenderState was
        // stored under the E.164 form (+98912...) by ingress. Lookup must unify them --
        // this exact mismatch caused the inbox/spam split.
        val tele = FakeTelephonyDataSource(listOf(conv(7, "09123456789")))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope, e164Normalizer)
        db.senderStateDao.upsert(
            SenderStateEntity(normalizedAddress = "+989123456789", state = ThreadSpamState.SPAM, spamCount = 1, isUserOverride = true)
        )
        val inbox = repo.observeConversations().first()
        val spam = repo.observeSpam().first()
        assertTrue(inbox.isEmpty())
        assertEquals(listOf(7L), spam.map { it.threadId.value })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `markSpam then markNotSpam moves conversation out of spam`() = runTest {
        val addr = "+989123456789" // already E.164 -- same key in repo + SpamRepository (no context)
        val tele = FakeTelephonyDataSource(listOf(conv(9, addr)))
        val db = NoSpamDatabase.inMemory()
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = ConversationsRepository(tele, db, testScope, e164Normalizer)
        val spamRepo = SpamRepository(db, com.nospam.nospam.core.testing.FakeSpamClassifier.alwaysSpam())
        spamRepo.markSpam(ThreadId(9), addr)
        assertEquals(listOf(9L), repo.observeSpam().first().map { it.threadId.value })
        assertEquals(0, repo.observeConversations().first().size)

        spamRepo.markNotSpam(ThreadId(9), addr)
        assertTrue(repo.observeSpam().first().isEmpty())
        assertEquals(listOf(9L), repo.observeConversations().first().map { it.threadId.value })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `spam page lists the newest message first whatever order telephony returns`() = runTest {
        val tele = FakeTelephonyDataSource(listOf(
            conv(1, "+98911", date = 10), conv(2, "+98912", date = 30), conv(3, "+98913", date = 20),
        ))
        val db = NoSpamDatabase.inMemory()
        listOf("+98911", "+98912", "+98913").forEach {
            db.senderStateDao.upsert(SenderStateEntity(it, ThreadSpamState.SPAM, isUserOverride = true))
        }
        val repo = ConversationsRepository(tele, db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(listOf(2L, 3L, 1L), repo.observeSpam().first().map { it.threadId.value })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `archived page lists the newest message first and ignores pins and archive order`() = runTest {
        val tele = FakeTelephonyDataSource(listOf(
            conv(1, "+98911", date = 10), conv(2, "+98912", date = 30), conv(3, "+98913", date = 20),
        ))
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(tele, db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        // Archived oldest-message-first, the way that would expose a sort by archive time.
        repo.archive(ThreadId(1)); repo.archive(ThreadId(3)); repo.archive(ThreadId(2))
        assertEquals(listOf(2L, 3L, 1L), repo.observeArchived().first().map { it.threadId.value })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `archiving drops the pin, and it stays dropped after unarchiving`() = runTest {
        val tele = FakeTelephonyDataSource(listOf(conv(1, "+98911", date = 10), conv(2, "+98912", date = 20)))
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(tele, db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        repo.setPin(ThreadId(1), true)
        assertTrue(repo.observeConversations().first().first { it.threadId.value == 1L }.isPinned)

        repo.archive(ThreadId(1))
        repo.unarchive(ThreadId(1))
        val back = repo.observeConversations().first()
        assertFalse(back.first { it.threadId.value == 1L }.isPinned)
        // Back to its date position: the pin no longer floats it above the newer one.
        assertEquals(listOf(2L, 1L), back.map { it.threadId.value })
    }
}

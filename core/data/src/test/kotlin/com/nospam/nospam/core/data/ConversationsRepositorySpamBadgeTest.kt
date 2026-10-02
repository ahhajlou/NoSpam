// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbox "Suspected spam" badge follows the conversation's messages, not the
 * sender's history; and the drawer counts what reached Spam since it was seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsRepositorySpamBadgeTest {

    private fun conv(id: Long, address: String, date: Long = id, name: String? = null) =
        Conversation(ThreadId(id), listOf(Participant(address, displayName = name)), "hi", date, 1, true)

    private fun spam(messageId: Long, threadId: Long, address: String) =
        MessageVerdictEntity(messageId, threadId, address, isSpam = true, score = 2.0)

    private suspend fun ConversationsRepository.badge(threadId: Long) =
        observeConversations().first().first { it.threadId.value == threadId }.hasSuspectedSpam

    @Test fun `the badge shows while the conversation holds a flagged message`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(FakeTelephonyDataSource(listOf(conv(1, "+98911"))), db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        db.senderStateDao.upsert(SenderStateEntity("+98911", ThreadSpamState.MIXED, spamCount = 1, hamCount = 1))
        db.messageVerdictDao.insert(spam(10, 1, "+98911"))

        assertTrue(repo.badge(1))
    }

    @Test fun `deleting the flagged message removes the badge but keeps the sender's history`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(FakeTelephonyDataSource(listOf(conv(1, "+98911"))), db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        db.senderStateDao.upsert(SenderStateEntity("+98911", ThreadSpamState.MIXED, spamCount = 1, hamCount = 1))
        db.messageVerdictDao.insert(spam(10, 1, "+98911"))

        SpamRepository(db, FakeSpamClassifier.alwaysHam()).onMessageDeleted(10)

        assertFalse(repo.badge(1))
        val state = db.senderStateDao.getByAddress("+98911")!!
        assertEquals(ThreadSpamState.MIXED, state.state)
        assertEquals(1, state.spamCount)
    }

    @Test fun `marking the message Not spam removes the badge`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = ConversationsRepository(FakeTelephonyDataSource(listOf(conv(1, "+98911"))), db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        db.senderStateDao.upsert(SenderStateEntity("+98911", ThreadSpamState.MIXED, spamCount = 1, hamCount = 1))
        db.messageVerdictDao.insert(spam(10, 1, "+98911"))

        SpamRepository(db, FakeSpamClassifier.alwaysHam()).markMessageNotSpam(10)

        assertFalse(repo.badge(1))
    }

    @Test fun `no badge for a saved contact or a sender marked Not spam`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource(listOf(conv(1, "+98911", name = "Saved"), conv(2, "+98922")))
        val repo = ConversationsRepository(tele, db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        db.senderStateDao.upsert(SenderStateEntity("+98922", ThreadSpamState.TRUSTED, spamCount = 1, isUserOverride = true))
        db.messageVerdictDao.insert(spam(10, 1, "+98911"))
        db.messageVerdictDao.insert(spam(20, 2, "+98922"))

        assertFalse(repo.badge(1))
        assertFalse(repo.badge(2))
    }

    @Test fun `the new spam count counts conversations in Spam that arrived after the last look`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val tele = FakeTelephonyDataSource(listOf(conv(1, "+98911", date = 100), conv(2, "+98922", date = 300), conv(3, "+98933", date = 400)))
        val repo = ConversationsRepository(tele, db, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        db.senderStateDao.upsert(SenderStateEntity("+98911", ThreadSpamState.SPAM, spamCount = 2))
        db.senderStateDao.upsert(SenderStateEntity("+98922", ThreadSpamState.SPAM, spamCount = 2))
        db.senderStateDao.upsert(SenderStateEntity("+98933", ThreadSpamState.MIXED, spamCount = 2, hamCount = 1))
        val seenAt = MutableStateFlow(0L)

        assertEquals(2, repo.observeNewSpamCount(seenAt).first())
        seenAt.value = 200
        assertEquals(1, repo.observeNewSpamCount(seenAt).first())
        seenAt.value = 300
        assertEquals(0, repo.observeNewSpamCount(seenAt).first())
    }
}

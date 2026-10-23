// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakePreferencesDataSource
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which messages a conversation's notification lists, and the count it shows. */
class MessageNotificationRepositoryTest {

    private val thread = ThreadId(7)
    private val telephony = FakeTelephonyDataSource()
    private val db = NoSpamDatabase.inMemory()
    private val prefs = FakePreferencesDataSource()
    private var clock = 10_000L
    private val repository = MessageNotificationRepository(
        telephony, SpamRepository(db, FakeSpamClassifier.alwaysHam()), prefs, now = { clock },
    )

    private fun message(id: Long, date: Long, read: Boolean = false, type: MessageType = MessageType.INBOX) =
        Message(MessageId(id), thread, "+15551000001", "message $id", date, type, read)

    private suspend fun ids() = repository.unread(thread, includeFlagged = false).messages.map { it.id.value }

    @Test fun `unread received messages are listed oldest first`() = runTest {
        telephony.emitMessages(thread, listOf(message(3, 3_000), message(1, 1_000), message(2, 2_000)))

        assertEquals(listOf(1L, 2L, 3L), ids())
    }

    @Test fun `read and sent messages are not listed or counted`() = runTest {
        telephony.emitMessages(
            thread,
            listOf(
                message(1, 1_000, read = true),
                message(2, 2_000, type = MessageType.SENT),
                message(3, 3_000),
            ),
        )

        val unread = repository.unread(thread, includeFlagged = false)

        assertEquals(listOf(3L), unread.messages.map { it.id.value })
        assertEquals(1, unread.unreadCount)
    }

    @Test fun `seven are listed and every unread message is counted`() = runTest {
        telephony.emitMessages(thread, (1L..12L).map { message(it, it * 1_000) })

        val unread = repository.unread(thread, includeFlagged = false)

        assertEquals((6L..12L).toList(), unread.messages.map { it.id.value })
        assertEquals(12, unread.unreadCount)
    }

    @Test fun `messages up to the seen time are not listed again`() = runTest {
        telephony.emitMessages(thread, listOf(message(1, 1_000), message(2, 2_000)))
        clock = 2_000
        repository.markSeen(thread)
        telephony.emitMessages(thread, listOf(message(1, 1_000), message(2, 2_000), message(3, 3_000)))

        assertEquals(listOf(3L), ids())
    }

    @Test fun `seen times are per conversation`() = runTest {
        telephony.emitMessages(thread, listOf(message(1, 1_000)))
        repository.markSeen(ThreadId(8), at = 5_000)

        assertEquals(listOf(1L), ids())
    }

    @Test fun `messages flagged spam are left out unless asked for`() = runTest {
        telephony.emitMessages(thread, listOf(message(1, 1_000), message(2, 2_000)))
        db.messageVerdictDao.insert(MessageVerdictEntity(1, thread.value, "+15551000001", isSpam = true, score = 0.9))

        assertEquals(listOf(2L), ids())
        assertEquals(listOf(1L, 2L), repository.unread(thread, includeFlagged = true).messages.map { it.id.value })
    }

    @Test fun `a message the user marked not spam is listed`() = runTest {
        telephony.emitMessages(thread, listOf(message(1, 1_000)))
        db.messageVerdictDao.insert(
            MessageVerdictEntity(1, thread.value, "+15551000001", isSpam = true, score = 0.9, userLabel = false),
        )

        assertEquals(listOf(1L), ids())
    }

    @Test fun `an unreadable seen time lists every unread message`() = runTest {
        telephony.emitMessages(thread, listOf(message(1, 1_000)))
        repository.markSeen(thread, at = 5_000)
        prefs.failReads = true

        assertEquals(listOf(1L), ids())
    }

    @Test fun `a failed seen write is dropped`() = runTest {
        telephony.emitMessages(thread, listOf(message(1, 1_000)))
        prefs.failWrites = true

        repository.markSeen(thread, at = 5_000)

        assertEquals(listOf(1L), ids())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What the export file may contain: received messages only, text masked. */
class ExportRepositoryTest {

    private val telephony = FakeTelephonyDataSource()

    private fun message(id: Long, type: MessageType, body: String = "text $id", address: String = "+989120000000") =
        Message(MessageId(id), ThreadId(1), address, body, 1_000L * id, type, true)

    private val contact = "+989121111111"
    private val stranger = "+989122222222"

    private fun contactAndStranger() {
        telephony.contacts[contact] = Participant(contact, displayName = "Sara")
        telephony.allMessages += listOf(
            message(1, MessageType.INBOX, address = contact),
            message(2, MessageType.INBOX, address = stranger),
            message(3, MessageType.INBOX, address = contact),
            message(4, MessageType.INBOX, address = stranger),
        )
    }

    private fun repository(mask: (String) -> String = { "<$it>" }) =
        ExportRepository(telephony, NoSpamDatabase.inMemory(), mask)

    @Test fun `only received messages are exported`() = runTest {
        telephony.allMessages += MessageType.entries.mapIndexed { i, type -> message(i + 1L, type) }

        val exported = repository().receivedMessages()

        assertEquals(listOf(MessageType.INBOX), exported.map { it.type })
    }

    @Test fun `the text is masked and nothing else changes`() = runTest {
        val original = message(1, MessageType.INBOX, body = "card 6037991234567890")
        telephony.allMessages += original

        val exported = repository(mask = { it.replace(Regex("\\d{16}"), "[CARD]") }).receivedMessages().single()

        assertEquals(original.copy(body = "card [CARD]"), exported)
    }

    @Test fun `a masking failure exports nothing, even when the native library is missing`() = runTest {
        telephony.allMessages += message(1, MessageType.INBOX)
        val repository = repository(mask = { throw UnsatisfiedLinkError("no spam_core_jni") })

        try {
            repository.receivedMessages()
            fail("expected MaskingFailedException")
        } catch (e: MaskingFailedException) {
            assertTrue(e.cause is UnsatisfiedLinkError)
        }
    }

    @Test fun `messages from saved contacts are left out by default`() = runTest {
        contactAndStranger()

        val exported = repository().receivedMessages()

        assertEquals(listOf(2L, 4L), exported.map { it.id.value })
    }

    @Test fun `messages from saved contacts are exported when asked`() = runTest {
        contactAndStranger()

        val exported = repository().receivedMessages(includeContacts = true)

        assertEquals(listOf(1L, 2L, 3L, 4L), exported.map { it.id.value })
    }

    @Test fun `each sender is looked up once`() = runTest {
        contactAndStranger()

        repository().receivedMessages()

        assertEquals(listOf(contact, stranger), telephony.lookedUpContacts)
    }
}

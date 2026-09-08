package com.nospam.nospam.feature.export

import com.nospam.nospam.core.database.dao.MessageVerdictDao
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExportJsonlTest {

    @Test
    fun `messageToJsonLine formats correctly with label`() {
        val json = messageToJsonLine("hwid123", 1690000000L, "1234567890", "Hello World", "spam")
        val expected = """{"hwid":"hwid123","date":1690000000,"address":"1234567890","text":"Hello World","label":"spam"}"""
        assertEquals(expected, json)
        // Note: If your Json config adds spaces, adjust expected string accordingly.
    }

    @Test
    fun `messageToJsonLine formats correctly without label`() {
        val json = messageToJsonLine("hwid123", 1690000000L, "1234567890", "Hello World", null)
        val expected = """{"hwid":"hwid123","date":1690000000,"address":"1234567890","text":"Hello World","label":null}"""
        assertEquals(expected, json)
    }

    @Test
    fun `writeJsonl writes multiple lines`() = runTest {
        val messages = listOf(
            Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true),
            Message(MessageId(2), ThreadId(1), "222", "Msg 2", 2000L, MessageType.SENT, true)
        )
        val outputStream = ByteArrayOutputStream()
        writeJsonl(outputStream, messages, "hwid999")
        val result = outputStream.toString(Charsets.UTF_8.name())

        // Filter out the empty string caused by the trailing newline
        val lines = result.lines().filter { it.isNotBlank() }

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Msg 1"))
        assertTrue(lines[1].contains("Msg 2"))
    }

    @Test
    fun `writeJsonlWithLabels calls labelProvider`() = runTest {
        val messages = listOf(
            Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true)
        )
        val outputStream = ByteArrayOutputStream()
        writeJsonlWithLabels(outputStream, messages, "hwid999") { msg ->
            if (msg.address == "111") "spam" else null
        }
        val result = outputStream.toString(Charsets.UTF_8.name())
        assertTrue(result.contains("\"label\":\"spam\""))
    }

    @Test
    fun `resolveLabel returns spam when isSpam is true`() = runTest {
        val message = Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true)
        val mockDao = mockk<MessageVerdictDao>()
        // Adjust parameters below to exactly match your MessageVerdictEntity data class
        coEvery { mockDao.getByMessageId(1) } returns MessageVerdictEntity(
            messageId = 1, threadId = 1, normalizedAddress = "111", score = 0.9, isSpam = true, userLabel = null
        )

        val label = resolveLabel(message, mockDao)
        assertEquals("spam", label)
    }

    @Test
    fun `resolveLabel returns ham when isSpam is false`() = runTest {
        val message = Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true)
        val mockDao = mockk<MessageVerdictDao>()
        coEvery { mockDao.getByMessageId(1) } returns MessageVerdictEntity(
            messageId = 1, threadId = 1, normalizedAddress = "111", score = 0.1, isSpam = false, userLabel = null
        )

        val label = resolveLabel(message, mockDao)
        assertEquals("ham", label)
    }

    @Test
    fun `resolveLabel returns null when dao is null`() = runTest {
        val message = Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true)
        val label = resolveLabel(message, null)
        assertNull(label)
    }

    @Test
    fun `resolveLabel returns null when entity not found`() = runTest {
        val message = Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true)
        val mockDao = mockk<MessageVerdictDao>()
        coEvery { mockDao.getByMessageId(1) } returns null

        val label = resolveLabel(message, mockDao)
        assertNull(label)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.export

import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for the pure, deterministic JSONL functions in ExportViewModel.kt,
 * per feature-export.md.
 */
class ExportJsonlTest {

    @Test
    fun `messageToJsonLine formats correctly with label`() {
        val json = messageToJsonLine("install-1", 1690000000L, "1234567890", "Hello World", "spam")
        val expected =
            """{"installId":"install-1","timestamp":1690000000,"address":"1234567890","text":"Hello World","label":"spam"}"""
        assertEquals(expected, json)
    }

    @Test
    fun `messageToJsonLine writes an explicit null label rather than omitting the key`() {
        val json = messageToJsonLine("install-1", 1690000000L, "1234567890", "Hello World", null)
        assertTrue("expected explicit null label key", json.contains("\"label\":null"))
        val expected =
            """{"installId":"install-1","timestamp":1690000000,"address":"1234567890","text":"Hello World","label":null}"""
        assertEquals(expected, json)
    }

    @Test
    fun `writeJsonl writes one null-labelled line per message in order`() {
        val messages = listOf(
            Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true),
            Message(MessageId(2), ThreadId(1), "222", "Msg 2", 2000L, MessageType.SENT, true),
        )
        val outputStream = ByteArrayOutputStream()
        writeJsonl(outputStream, messages, "install-9")
        val lines = outputStream.toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Msg 1"))
        assertTrue(lines[1].contains("Msg 2"))
        assertTrue("writeJsonl never computes a label", lines.all { it.contains("\"label\":null") })
    }

    @Test
    fun `writeJsonl on an empty message list writes nothing and does not throw`() {
        val outputStream = ByteArrayOutputStream()
        writeJsonl(outputStream, emptyList(), "install-9")
        assertEquals("", outputStream.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `writeJsonlWithLabels calls labelProvider once per message in order`() = runTest {
        val messages = listOf(
            Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true),
            Message(MessageId(2), ThreadId(1), "222", "Msg 2", 2000L, MessageType.SENT, true),
        )
        val outputStream = ByteArrayOutputStream()
        writeJsonlWithLabels(outputStream, messages, "install-9") { msg ->
            if (msg.address == "111") "spam" else null
        }
        val lines = outputStream.toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"label\":\"spam\""))
        assertTrue(lines[1].contains("\"label\":null"))
    }

    @Test
    fun `writeJsonl and writeJsonlWithLabels with an always-null provider are byte-identical`() = runTest {
        val messages = listOf(
            Message(MessageId(1), ThreadId(1), "111", "Msg 1", 1000L, MessageType.INBOX, true),
            Message(MessageId(2), ThreadId(1), "222", "Msg 2", 2000L, MessageType.SENT, true),
        )
        val plain = ByteArrayOutputStream()
        writeJsonl(plain, messages, "install-9")
        val viaLabels = ByteArrayOutputStream()
        writeJsonlWithLabels(viaLabels, messages, "install-9") { null }

        assertEquals(
            plain.toString(Charsets.UTF_8.name()),
            viaLabels.toString(Charsets.UTF_8.name()),
        )
    }
}

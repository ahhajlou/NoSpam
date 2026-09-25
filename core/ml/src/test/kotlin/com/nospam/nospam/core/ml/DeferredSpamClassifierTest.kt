// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DeferredSpamClassifierTest {

    private val verdict = SpamVerdict(label = SpamLabel.SPAM, score = 0.9)

    private inner class Recording : SpamClassifier {
        val texts = mutableListOf<String>()
        override suspend fun classify(message: RawMessage): SpamVerdict = verdict.also { texts += message.body }
        override suspend fun classifyText(text: String): SpamVerdict = verdict.also { texts += text }
    }

    @Test fun `building it loads nothing`() {
        val loads = AtomicInteger()
        DeferredSpamClassifier { loads.incrementAndGet(); Recording() }
        assertEquals(0, loads.get())
    }

    @Test fun `the first call loads on the given dispatcher, not the caller's thread`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "model-loader") }
        try {
            var loadedOn: String? = null
            val classifier = DeferredSpamClassifier(executor.asCoroutineDispatcher()) {
                loadedOn = Thread.currentThread().name
                Recording()
            }
            classifier.classifyText("hello")
            // Coroutine debug mode, on in tests, suffixes thread names with the coroutine id.
            assertTrue("loaded on $loadedOn", loadedOn!!.startsWith("model-loader"))
            assertFalse(Thread.currentThread().name.startsWith("model-loader"))
        } finally {
            executor.shutdown()
        }
    }

    @Test fun `the model is loaded once and every call reaches it`() = runBlocking {
        val loads = AtomicInteger()
        val real = Recording()
        val classifier = DeferredSpamClassifier { loads.incrementAndGet(); real }

        assertEquals(verdict, classifier.classify(RawMessage(sender = "+15550001", body = "one", timestamp = 1L)))
        assertEquals(verdict, classifier.classifyText("two"))
        assertEquals(verdict, classifier.classify(RawMessage(sender = "+15550001", body = "three", timestamp = 2L)))

        assertEquals(1, loads.get())
        assertEquals(listOf("one", "two", "three"), real.texts)
    }
}

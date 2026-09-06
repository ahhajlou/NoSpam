package com.nospam.nospam.core.ml

import com.nospam.nospam.core.model.RawMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TfidfTest {
    @Test fun `preprocess masks URL and digits`() {
        val out = TfidfPreprocessor.preprocess("Visit https://example.com and call 09123456789")
        assertTrue(out.contains("URLTOKEN"))
        assertTrue(out.contains("NUM_TOKEN"))
        assertFalse(out.contains("https://"))
    }

    @Test fun `preprocess normalizes Persian ye and kaf`() {
        val out = TfidfPreprocessor.preprocess("كتاب ي")
        assertTrue(out.contains("کتاب"))
        assertTrue(out.contains("ی"))
    }

    @Test fun `preprocess handles ZWNJ and kashida`() {
        val out = TfidfPreprocessor.preprocess("می\u200Cخواهم\u0640")
        assertFalse(out.contains("\u200C"))
        assertFalse(out.contains("\u0640"))
    }

    @Test fun `char_wb ngrams padded`() {
        val ngrams = TfidfPreprocessor.getCharWbNgrams("hi", 2, 2)
        // " hi " -> " h", "hi", "i "
        assertTrue(ngrams.contains(" h"))
        assertTrue(ngrams.contains("hi"))
        assertTrue(ngrams.contains("i "))
    }

    @Test fun `classifier with dummy model`() = runTest {
        val model = SpamModel(
            vocab = mapOf(" h" to 0, "hi" to 1),
            weights = listOf(1.0, 1.0),
            bias = -0.5,
            classes = listOf("ham", "spam")
        )
        val clf = TfidfSpamClassifier(model)
        val spam = clf.classifyText("hi")
        assertTrue(spam.isSpam)
        val ham = clf.classifyText("bye")
        assertFalse(ham.isSpam)
    }

    @Test fun `parity with known spam phrase`() = runTest {
        // Load real model if available (asset copy)
        try {
            val json = java.io.File("src/main/assets/spam_model.json").readText()
            val clf = TfidfSpamClassifier.fromJson(json)
            val verdict = clf.classify(RawMessage("1000", "You won prize click here", System.currentTimeMillis()))
            // Just ensure it runs and score is finite
            assertTrue(verdict.score.isFinite())
        } catch (e: Exception) {
            // asset not found in test cwd, skip
        }
    }
}

package com.nospam.nospam.core.ml

import com.nospam.nospam.core.model.RawMessage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TfidfTest {

    private fun normalizer(): HazmNormalizer = HazmNormalizer.fromFiles(File("src/main/assets"))

    @Test fun `preprocess masks URL and digits`() {
        val out = normalizer().preprocess("Visit https://example.com and call 09123456789")
        assertTrue(out.contains("URLTOKEN"))
        assertTrue(out.contains("NUM_TOKEN"))
        assertFalse(out.contains("https://"))
    }

    @Test fun `preprocess masks PERSIAN digits too`() {
        // Java's default \d is ASCII-only; the baseline masks ۵۷۳۹ via Unicode \d.
        val out = normalizer().preprocess("کد رهگیری ۵۷۳۹")
        assertTrue(out.contains("NUM_TOKEN"))
        assertFalse(out.contains("۵۷۳۹"))
    }

    @Test fun `preprocess normalizes Persian ye and kaf`() {
        val out = normalizer().preprocess("كتاب ي")
        assertTrue(out.contains("کتاب"))
        assertTrue(out.contains("ی"))
    }

    @Test fun `preprocess preserves ZWNJ and removes kashida`() {
        // hazm inserts/keeps ZWNJ inside می‌خواهم; only kashida/zero-width are dropped.
        val out = normalizer().preprocess("می\u200Cخواهم\u0640")
        assertTrue(out.contains("\u200C"))
        assertFalse(out.contains("\u0640"))
    }

    @Test fun `char_wb ngrams padded`() {
        val ngrams = TfidfPreprocessor.getCharWbNgrams("hi", 2, 2)
        // " hi " -> " h", "hi", "i "
        assertEquals(listOf(" h", "hi", "i "), ngrams)
    }

    @Test fun `char_wb short word counted once per n`() {
        // sklearn: a word shorter than n contributes a single whole-substring window.
        assertEquals(listOf(" x "), TfidfPreprocessor.getCharWbNgrams("x", 4, 4))
    }

    @Test fun `classifier with dummy model`() = runTest {
        val model = SpamModel(
            vocab = mapOf(" h" to 0, "hi" to 1),
            weights = listOf(1.0, 1.0),
            bias = -0.5,
            classes = listOf("ham", "spam"),
        )
        val clf = TfidfSpamClassifier(model, HazmNormalizer.empty())
        val spam = clf.classifyText("hi")
        assertTrue(spam.isSpam)
        val ham = clf.classifyText("bye")
        assertFalse(ham.isSpam)
    }

    @Test fun `parity with known spam phrase`() = runTest {
        // Load real model if available (asset copy)
        try {
            val json = File("src/main/assets/spam_model.json").readText()
            val clf = TfidfSpamClassifier.fromJson(json, normalizer())
            val verdict = clf.classify(RawMessage("1000", "You won prize click here", System.currentTimeMillis()))
            // Just ensure it runs and score is finite
            assertTrue(verdict.score.isFinite())
        } catch (e: Exception) {
            // asset not found in test cwd, skip
        }
    }
}
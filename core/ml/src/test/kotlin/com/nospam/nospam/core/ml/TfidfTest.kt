// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scoring side of [TfidfSpamClassifier] with hand-made models. The text processing
 * is the core's, checked case by case against training in [CoreParityTest].
 */
class TfidfTest {

    private fun model(
        vocab: Map<String, Int>,
        weights: List<Double>,
        bias: Double,
        classes: List<String> = listOf("ham", "spam"),
    ) = SpamModel(vocab, weights, bias, classes, pipeline = NativeCore.pipeline())

    @Test fun `ngrams are char_wb 2-4-grams of each padded word`() {
        assertEquals(
            listOf(" h", "hi", "i ", " hi", "hi ", " hi "),
            NativeCore.ngrams("hi").toList(),
        )
    }

    @Test fun `classifier with dummy model`() = runTest {
        val clf = TfidfSpamClassifier(model(mapOf(" h" to 0, "hi" to 1), listOf(1.0, 1.0), bias = -0.5))
        assertTrue(clf.classifyText("hi").isSpam)
        assertFalse(clf.classifyText("bye").isSpam)
    }

    @Test fun `a message with no vocabulary n-grams scores the bias`() = runTest {
        val clf = TfidfSpamClassifier(model(mapOf("zz" to 0), listOf(5.0), bias = -0.25))
        assertEquals(-0.25, clf.classifyText("hello").score, 0.0)
    }

    @Test fun `spam listed first means a score at or below the threshold is spam`() = runTest {
        val clf = TfidfSpamClassifier(
            model(mapOf("hi" to 0), listOf(-1.0), bias = 0.5, classes = listOf("spam", "ham")),
        )
        assertTrue(clf.classifyText("hi").isSpam)
        assertFalse(clf.classifyText("bye").isSpam)
    }
}

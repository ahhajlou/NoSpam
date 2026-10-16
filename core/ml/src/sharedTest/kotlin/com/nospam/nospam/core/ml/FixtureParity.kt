// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Parity with training. `spam_fixture.json` is written with `spam_model.json` by
 * persian-sms-spam's `make export-android`: for each of the core's battery inputs,
 * every stage's output, the n-grams and the trained pipeline's `decision_function`
 * score, computed in Python on the same Rust core. The JVM test runs this against the
 * host build of the core and the device test against the phone's build, so each
 * checks the code it runs.
 */
internal object FixtureParity {

    @Serializable
    class Fixture(val pipeline: Pipeline, val cases: List<Case>)

    @Serializable
    class Case(
        val input: String,
        val anonymized: String,
        val urlsMasked: String,
        val numbersMasked: String,
        val output: String,
        val ngrams: List<String>,
        val score: Double,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Asserts that [classifier] and [NativeCore] reproduce every case in [fixtureJson]. */
    suspend fun check(fixtureJson: String, classifier: TfidfSpamClassifier) {
        val fixture = json.decodeFromString<Fixture>(fixtureJson)
        assertEquals("fixture pipeline", NativeCore.pipeline(), fixture.pipeline)
        assertEquals("model pipeline", fixture.pipeline, classifier.pipeline)
        assertTrue("the fixture has no cases", fixture.cases.isNotEmpty())
        fixture.cases.forEachIndexed { i, case ->
            val expected = PipelineStages(case.anonymized, case.urlsMasked, case.numbersMasked, case.output)
            assertEquals("stages of case $i", expected, NativeCore.pipelineStages(case.input))
            assertEquals("n-grams of case $i", case.ngrams, NativeCore.ngrams(case.input).toList())
            val verdict = classifier.classifyText(case.input)
            assertEquals("score of case $i", case.score, verdict.score, 1e-6)
            // A score within the tolerance of the threshold could round either way.
            if (kotlin.math.abs(case.score) > 1e-6) {
                assertEquals("verdict of case $i", case.score > 0.0, verdict.isSpam)
            }
        }
    }
}

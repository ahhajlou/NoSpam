// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.mldebug

import com.nospam.nospam.core.ml.NativeCore
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.ml.TfidfSpamClassifier
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class TestClassifier(
    private val verdictFor: (String) -> SpamVerdict,
) : SpamClassifier {
    override suspend fun classify(message: RawMessage): SpamVerdict = verdictFor(message.body)
    override suspend fun classifyText(text: String): SpamVerdict = verdictFor(text)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MlDebugViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `classify populates full pipeline trace`() = runTest(dispatcher) {
        val vm = MlDebugViewModel(TestClassifier { SpamVerdict(SpamLabel.SPAM, 1.5) }, dispatcher)
        vm.onInputChanged("You won! Claim your https://example.com prize now")
        advanceUntilIdle()

        vm.classify()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isClassifying)
        assertNull(state.error)
        val result = checkNotNull(state.result)
        assertEquals("You won! Claim your https://example.com prize now", result.input)
        // The Rust core masked the URL in its URL step and kept it masked.
        assertTrue("https://" in result.stages.anonymized)
        assertTrue("URLTOKEN" in result.stages.urlsMasked)
        assertTrue("URLTOKEN" in result.stages.normalized)
        assertEquals(NativeCore.ngrams(result.input).size, result.ngramCount)
        assertEquals(1.5, result.score, 0.0)
        assertTrue(result.isSpam)
        // Not the TF-IDF model, so there is no model pipeline to show.
        assertNull(result.pipeline)
        assertNull(result.coreGitSha)
    }

    @Test
    fun `the real model shows its pipeline and core commit`() = runTest(dispatcher) {
        // The model from the sibling core:ml module (test working dir is the feature
        // module dir, so ../../core/ml resolves from this module).
        val classifier = TfidfSpamClassifier.fromJson(File("../../core/ml/src/main/assets/spam_model.json").readText())
        val vm = MlDebugViewModel(classifier, dispatcher)
        vm.onInputChanged("سلام، فردا میبینمت")
        vm.classify()
        advanceUntilIdle()

        val result = checkNotNull(vm.uiState.value.result)
        assertEquals(NativeCore.pipeline(), result.pipeline)
        assertEquals(classifier.coreGitSha, result.coreGitSha)
        assertEquals(40, result.coreGitSha?.length)
    }

    @Test
    fun `ham verdict surfaces ham result and negative score`() = runTest(dispatcher) {
        val vm = MlDebugViewModel(TestClassifier { SpamVerdict(SpamLabel.HAM, -0.7) }, dispatcher)
        vm.onInputChanged("سلام، فردا میبینمت")
        vm.classify()
        advanceUntilIdle()

        val result = checkNotNull(vm.uiState.value.result)
        assertFalse(result.isSpam)
        assertEquals(-0.7, result.score, 0.0)
    }

    @Test
    fun `blank input never runs classification`() = runTest(dispatcher) {
        val vm = MlDebugViewModel(TestClassifier { SpamVerdict(SpamLabel.SPAM, 1.0) }, dispatcher)
        vm.classify()
        advanceUntilIdle()

        assertNull(vm.uiState.value.result)
        assertFalse(vm.uiState.value.isClassifying)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `null classifier surfaces unavailable error`() = runTest(dispatcher) {
        val vm = MlDebugViewModel(classifier = null)
        vm.onInputChanged("hello")
        vm.classify()
        advanceUntilIdle()

        assertEquals(MlDebugError.CLASSIFIER_UNAVAILABLE, vm.uiState.value.error)
        assertNull(vm.uiState.value.result)
    }

    @Test
    fun `classifier failure surfaces failed error`() = runTest(dispatcher) {
        val vm = MlDebugViewModel(TestClassifier { error("boom") }, dispatcher)
        vm.onInputChanged("hello")
        vm.classify()
        advanceUntilIdle()

        assertEquals(MlDebugError.FAILED, vm.uiState.value.error)
        assertFalse(vm.uiState.value.isClassifying)
        assertNull(vm.uiState.value.result)
    }

    @Test
    fun `editing input clears previous result`() = runTest(dispatcher) {
        val vm = MlDebugViewModel(TestClassifier { SpamVerdict(SpamLabel.HAM, -1.0) }, dispatcher)
        vm.onInputChanged("first")
        vm.classify()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.result != null)

        vm.onInputChanged("second")
        assertNull(vm.uiState.value.result)
        assertNull(vm.uiState.value.error)
        assertEquals("second", vm.uiState.value.input)
    }
}
// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.mldebug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.ml.NativeCore
import com.nospam.nospam.core.ml.Pipeline
import com.nospam.nospam.core.ml.PipelineStages
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.ml.TfidfSpamClassifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One full pass through the classifier pipeline, kept for inspection. */
data class MlResult(
    val input: String,
    /** What each step of the core made of the input; the n-grams come from the last. */
    val stages: PipelineStages,
    val ngramCount: Int,
    val score: Double,
    val isSpam: Boolean,
    /** The model's text pipeline; null when the classifier is not the TF-IDF model. */
    val pipeline: Pipeline? = null,
    /** The persian-sms-spam commit the model was exported from, if recorded. */
    val coreGitSha: String? = null,
)

enum class MlDebugError { CLASSIFIER_UNAVAILABLE, FAILED }

data class MlDebugUiState(
    val input: String = "",
    val isClassifying: Boolean = false,
    val result: MlResult? = null,
    val error: MlDebugError? = null,
)

/**
 * Types a raw message body and drives it through the on-device [SpamClassifier].
 * The classifier is passed in (null for previews/tests, or when it failed to
 * load); the pipeline trace is built from public `core:ml` APIs only — no
 * provider, DB or settings access. The trace comes from [NativeCore], the code
 * the classifier itself runs.
 */
class MlDebugViewModel(
    private val classifier: SpamClassifier? = null,
    private val classifierDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MlDebugUiState())
    val uiState: StateFlow<MlDebugUiState> = _uiState.asStateFlow()

    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(input = text, result = null, error = null)
    }

    fun classify() {
        val classifier = classifier ?: run {
            _uiState.value = _uiState.value.copy(error = MlDebugError.CLASSIFIER_UNAVAILABLE)
            return
        }
        val state = _uiState.value
        if (state.isClassifying || state.input.isBlank()) return
        val text = state.input
        viewModelScope.launch(classifierDispatcher) {
            _uiState.value = _uiState.value.copy(isClassifying = true, result = null, error = null)
            try {
                val verdict = classifier.classifyText(text)
                val model = classifier as? TfidfSpamClassifier
                _uiState.value = _uiState.value.copy(
                    isClassifying = false,
                    result = MlResult(
                        input = text,
                        stages = NativeCore.pipelineStages(text),
                        ngramCount = NativeCore.ngrams(text).size,
                        score = verdict.score,
                        isSpam = verdict.isSpam,
                        pipeline = model?.pipeline,
                        coreGitSha = model?.coreGitSha,
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isClassifying = false, error = MlDebugError.FAILED)
            }
        }
    }
}
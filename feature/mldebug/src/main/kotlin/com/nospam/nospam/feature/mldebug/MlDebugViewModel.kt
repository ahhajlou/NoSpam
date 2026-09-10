package com.nospam.nospam.feature.mldebug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.ml.HazmNormalizer
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.ml.TfidfPreprocessor
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
    /** What the model actually saw after URL/digit/hazm (Persian) normalization. */
    val normalized: String,
    val ngramCount: Int,
    val score: Double,
    val isSpam: Boolean,
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
 * The classifier is passed in (null for previews/tests); the pipeline trace is
 * built from public `core:ml` APIs only — no provider, DB or settings access.
 * The hazm normalizer is injected explicitly, or borrowed from a
 * [TfidfSpamClassifier] so the trace always matches the real preprocessing.
 */
class MlDebugViewModel(
    private val classifier: SpamClassifier? = null,
    private val classifierDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val normalizer: HazmNormalizer? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MlDebugUiState())
    val uiState: StateFlow<MlDebugUiState> = _uiState.asStateFlow()

    private fun effectiveNormalizer(): HazmNormalizer? =
        normalizer ?: (classifier as? TfidfSpamClassifier)?.normalizer

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
                val hazm = effectiveNormalizer()
                val normalized = hazm?.preprocess(text) ?: text
                val ngrams = TfidfPreprocessor.getCharWbNgrams(normalized)
                _uiState.value = _uiState.value.copy(
                    isClassifying = false,
                    result = MlResult(
                        input = text,
                        normalized = normalized,
                        ngramCount = ngrams.size,
                        score = verdict.score,
                        isSpam = verdict.isSpam,
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isClassifying = false, error = MlDebugError.FAILED)
            }
        }
    }
}
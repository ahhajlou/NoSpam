// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import android.content.Context
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.json.Json

/**
 * On-device classifier reproducing `baseline_tfidf.py`'s trained pipeline end to
 * end: [HazmNormalizer.preprocess] then sklearn's exact TfidfVectorizer.transform
 * (lowercase → char_wb n-grams → occurrence counts → 1+ln(sublinear_tf) → idf →
 * L2 row normalization) dotted with the LinearSVC coefficients plus intercept.
 *
 * A score > [threshold] maps to `classes[1]`; a score <= threshold to
 * `classes[0]`. This is the binary-LinearSVC convention used at training time.
 *
 * All regexes/regex sets are compiled once per instance ([normalizer] shares its
 * precompiled state), so steady-state classification stays linear in message
 * length — microseconds for a typical SMS — which is the whole reason tf-idf
 * beat BERT for this filter.
 */
class TfidfSpamClassifier(
    private val model: SpamModel,
    val normalizer: HazmNormalizer,
    private val threshold: Double = 0.0,
) : SpamClassifier {

    override suspend fun classify(message: RawMessage): SpamVerdict =
        classifyText(message.body)

    override suspend fun classifyText(text: String): SpamVerdict {
        val processed = normalizer.preprocess(text)
        val score = decision(processed)
        val isSpam = when (model.classes.indexOf("spam")) {
            0 -> score <= threshold
            else -> score > threshold
        }
        return SpamVerdict(
            label = if (isSpam) SpamLabel.SPAM else SpamLabel.HAM,
            score = score,
            isSpam = isSpam,
        )
    }

    /**
     * sklearn's transform + decision_function. `normalized` is the output of
     * [HazmNormalizer.preprocess]; this is exactly what the reference pipeline
     * feeds into the TfidfVectorizer at inference time.
     */
    private fun decision(normalized: String): Double {
        val minN = model.ngramRange?.getOrNull(0) ?: 2
        val maxN = model.ngramRange?.getOrNull(1) ?: 4
        val useSublinear = model.sublinearTf ?: true
        val useL2 = (model.norm ?: "l2") == "l2"
        val idf = model.idf

        val lowered = if (model.lowercase ?: true) normalized.lowercase(Locale.ROOT) else normalized
        val ngrams = TfidfPreprocessor.getCharWbNgrams(lowered, minN, maxN)

        val counts = HashMap<String, Int>()
        for (ngram in ngrams) counts[ngram] = (counts[ngram] ?: 0) + 1

        var sum = 0.0
        var sumSquares = 0.0
        for ((ngram, count) in counts) {
            val idx = model.vocab[ngram] ?: continue
            if (idx < 0 || idx >= model.weights.size) continue
            var value = if (useSublinear) 1.0 + ln(count.toDouble()) else count.toDouble()
            if (idf != null && idx < idf.size) value *= idf[idx]
            sum += value * model.weights[idx]
            if (useL2) sumSquares += value * value
        }
        // Normalize the (sparse) feature row by its L2 norm, then dot with coef.
        // A zero row is left unnormalized (all values stay 0) → decision = bias,
        // matching sklearn's behavior for documents with no vocabulary n-grams.
        return if (useL2 && sumSquares > 0.0) model.bias + sum / sqrt(sumSquares) else model.bias + sum
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromAsset(context: Context, threshold: Double = 0.0): TfidfSpamClassifier {
            val modelString = context.assets.open("spam_model.json").bufferedReader().use { it.readText() }
            return TfidfSpamClassifier(json.decodeFromString<SpamModel>(modelString), HazmNormalizer.fromAssets(context), threshold)
        }

        fun fromJson(modelJson: String, normalizer: HazmNormalizer, threshold: Double = 0.0): TfidfSpamClassifier =
            TfidfSpamClassifier(json.decodeFromString<SpamModel>(modelJson), normalizer, threshold)
    }
}
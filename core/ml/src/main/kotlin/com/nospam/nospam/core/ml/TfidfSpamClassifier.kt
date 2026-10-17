// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import android.content.Context
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.json.Json

/**
 * On-device classifier reproducing persian-sms-spam's trained TF-IDF + LinearSVC
 * pipeline end to end. [NativeCore] turns the raw message into `char_wb`
 * n-grams with the same Rust code training uses; this class does the rest of
 * sklearn's TfidfVectorizer.transform (occurrence counts → 1+ln(sublinear_tf) →
 * idf → L2 row normalization) dotted with the LinearSVC coefficients plus
 * intercept.
 *
 * A score > [threshold] maps to `classes[1]`; a score <= threshold to
 * `classes[0]`. This is the binary-LinearSVC convention used at training time.
 *
 * A model whose [SpamModel.pipeline] differs from the core's is refused with
 * `IllegalStateException` when the classifier is built, which the callers treat
 * as a failed classifier.
 */
class TfidfSpamClassifier(
    private val model: SpamModel,
    private val threshold: Double = 0.0,
) : SpamClassifier {

    init {
        val core = NativeCore.pipeline()
        check(model.pipeline == core) {
            "spam model was trained with pipeline ${model.pipeline}, but the app's core is $core"
        }
    }

    /** The pipeline the model was trained with, equal to the core's. */
    val pipeline: Pipeline get() = model.pipeline

    /** The persian-sms-spam commit the model was exported from, if recorded. */
    val coreGitSha: String? get() = model.coreGitSha

    override suspend fun classify(message: RawMessage): SpamVerdict =
        classifyText(message.body)

    override suspend fun classifyText(text: String): SpamVerdict {
        val score = decision(NativeCore.ngrams(text))
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

    /** sklearn's transform + decision_function over one message's n-grams. */
    private fun decision(ngrams: Array<String>): Double {
        val useSublinear = model.sublinearTf ?: true
        val useL2 = (model.norm ?: "l2") == "l2"
        val idf = model.idf

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
            return fromJson(modelString, threshold)
        }

        /**
         * The Rust core compiles its rules ([NativeCore.warmUp]) on a second thread
         * while this one parses the model, which takes longer: the first message no
         * longer pays for the compile, and the load takes no longer. A core that
         * fails to load there is reported by its first use here.
         */
        fun fromJson(modelJson: String, threshold: Double = 0.0): TfidfSpamClassifier {
            val warmUp = thread(isDaemon = true, name = "spam-core-warm-up") { runCatching { NativeCore.warmUp() } }
            return TfidfSpamClassifier(json.decodeFromString<SpamModel>(modelJson), threshold)
                .also { warmUp.join() }
        }
    }
}

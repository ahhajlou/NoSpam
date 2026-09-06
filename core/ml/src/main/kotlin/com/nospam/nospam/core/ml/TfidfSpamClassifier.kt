package com.nospam.nospam.core.ml

import android.content.Context
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamLabel
import com.nospam.nospam.core.model.SpamVerdict
import kotlinx.serialization.json.Json

class TfidfSpamClassifier(
    private val model: SpamModel,
    private val threshold: Double = 0.0
) : SpamClassifier {

    override suspend fun classify(message: RawMessage): SpamVerdict =
        classifyText(message.body)

    override suspend fun classifyText(text: String): SpamVerdict {
        val processed = TfidfPreprocessor.preprocess(text)
        val ngrams = TfidfPreprocessor.getCharWbNgrams(processed)
        var score = model.bias
        for (ngram in ngrams) {
            val idx = model.vocab[ngram]
            if (idx != null && idx < model.weights.size) {
                score += model.weights[idx]
            }
        }
        val label = if (score > threshold) {
            if (model.classes.size > 1) {
                when (model.classes[1]) {
                    "spam" -> SpamLabel.SPAM
                    else -> SpamLabel.SPAM
                }
            } else SpamLabel.SPAM
        } else SpamLabel.HAM

        // Map string classes to label, handling both orders
        val isSpam = when {
            model.classes.getOrNull(1) == "spam" && score > threshold -> true
            model.classes.getOrNull(0) == "spam" && score <= threshold -> true
            else -> label == SpamLabel.SPAM
        }

        return SpamVerdict(
            label = if (isSpam) SpamLabel.SPAM else SpamLabel.HAM,
            score = score,
            isSpam = isSpam
        )
    }

    companion object {
        fun fromAsset(context: Context, threshold: Double = 0.0): TfidfSpamClassifier {
            val jsonString = context.assets.open("spam_model.json").bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            val model = json.decodeFromString<SpamModel>(jsonString)
            return TfidfSpamClassifier(model, threshold)
        }

        fun fromJson(jsonString: String, threshold: Double = 0.0): TfidfSpamClassifier {
            val json = Json { ignoreUnknownKeys = true }
            val model = json.decodeFromString<SpamModel>(jsonString)
            return TfidfSpamClassifier(model, threshold)
        }
    }
}

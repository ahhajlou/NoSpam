package com.example.nospam.ml

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// 1. Data class matching the JSON structure
data class SpamModel(
    val vocab: Map<String, Int>,
    val weights: List<Double>,
    val bias: Double,
    val classes: List<String>
)

class SpamDetector(context: Context) {
    private val model: SpamModel

    init {
        // Load the JSON from assets
        val jsonString = context.assets.open("spam_model.json").bufferedReader().use { it.readText() }
        val type = object : TypeToken<SpamModel>() {}.type
        model = Gson().fromJson(jsonString, type)
    }

    /**
     * Predicts if the text is spam or ham.
     * Returns the class name (e.g., "spam") and the confidence score.
     */
    fun predict(text: String): Pair<String, Double> {
        val processed = preprocess(text)
        val ngrams = getCharWbNgrams(processed, minN = 2, maxN = 4)

        // Start with the bias
        var score = model.bias

        // Add weight for every matching n-gram (TF-IDF logic simplified for inference)
        for (ngram in ngrams) {
            val index = model.vocab[ngram]
            if (index != null) {
                score += model.weights[index]
            }
        }

        // LinearSVC logic: score > 0 predicts classes[1], else classes[0]
        val predictedClass = if (score > 0) model.classes[1] else model.classes[0]

        return Pair(predictedClass, score)
    }

    // 2. Replicates your Python preprocess() function
    private fun preprocess(text: String): String {
        var t = text
        // Mask URLs
        t = t.replace(Regex("(?:https?://[^\\s]+)|(?:www\\.[^\\s]+)|(?:\\b[a-zA-Z0-9][a-zA-Z0-9\\-]*(?:\\.[a-zA-Z0-9\\-]+)+(?:/[^\\s]*)?)"), " URLTOKEN ")
        // Mask digits
        t = t.replace(Regex("\\d+"), " NUM_TOKEN ")
        // Basic Persian normalization (Arabic to Persian)
        t = t.replace("ي", "ی").replace("ك", "ک")
        // Remove common diacritics (matches hazm behavior)
        t = t.replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        return t.trim()
    }

    // 3. Replicates sklearn's analyzer="char_wb" exactly
    private fun getCharWbNgrams(text: String, minN: Int, maxN: Int): List<String> {
        val ngrams = mutableListOf<String>()
        // Split by whitespace to get "words"
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

        for (word in words) {
            // sklearn pads each word with a space on both sides for char_wb
            val padded = " $word "
            for (n in minN..maxN) {
                for (i in 0..(padded.length - n)) {
                    ngrams.add(padded.substring(i, i + n))
                }
            }
        }
        return ngrams
    }
}
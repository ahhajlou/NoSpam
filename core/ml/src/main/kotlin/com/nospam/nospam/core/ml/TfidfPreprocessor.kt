package com.nospam.nospam.core.ml

/**
 * Character n-gram feature builder matching sklearn's
 * `CountVectorizer(analyzer="char_wb")` exactly (sklearn 1.9.0): normalize runs
 * of whitespace, split on whitespace, pad each word with one space on each side,
 * then for each n in [minN, maxN] slide a window across the padded word — a
 * short word (< n chars after padding) contributes a single whole-substring
 * window and stops the n-loop, exactly like the Python loop.
 */
object TfidfPreprocessor {

    private val whitespaceRegex = Regex("(?u)\\s+")

    fun getCharWbNgrams(text: String, minN: Int = 2, maxN: Int = 4): List<String> {
        val ngrams = mutableListOf<String>()
        val words = whitespaceRegex.split(text).filter { it.isNotEmpty() }
        for (word in words) {
            val padded = " $word "
            val length = padded.length
            for (n in minN..maxN) {
                var offset = 0
                ngrams.add(padded.substring(0, (offset + n).coerceAtMost(length)))
                while (offset + n < length) {
                    offset += 1
                    ngrams.add(padded.substring(offset, (offset + n).coerceAtMost(length)))
                }
                if (offset == 0) break
            }
        }
        return ngrams
    }
}
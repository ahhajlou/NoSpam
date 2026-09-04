package com.example.nospam.core.ml

object TfidfPreprocessor {
    private val urlRegex = Regex("""(?:https?://[^\s]+)|(?:www\.[^\s]+)|(?:\b[a-zA-Z0-9][a-zA-Z0-9-]*(?:\.[a-zA-Z0-9-]+)+(?:/[^\s]*)?)""")
    private val digitsRegex = Regex("""\d+""")
    private val diacriticsRegex = Regex("""[\u064B-\u065F\u0670]""")
    private val whitespaceRegex = Regex("""\s+""")

    fun preprocess(text: String): String {
        var t = text
        t = t.replace(urlRegex, " URLTOKEN ")
        t = t.replace(digitsRegex, " NUM_TOKEN ")
        t = t.replace("ي", "ی").replace("ك", "ک")
        // ZWNJ handling: normalize to space for tokenization, preserve for Persian
        t = t.replace("\u200C", " ") // ZWNJ -> space
        t = t.replace("\u0640", "") // kashida removal
        t = t.replace("\u200B", "") // zero-width space
        t = t.replace(diacriticsRegex, "")
        return t.trim().replace(whitespaceRegex, " ")
    }

    fun getCharWbNgrams(text: String, minN: Int = 2, maxN: Int = 4): List<String> {
        val ngrams = mutableListOf<String>()
        val words = text.split(whitespaceRegex).filter { it.isNotEmpty() }
        for (word in words) {
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

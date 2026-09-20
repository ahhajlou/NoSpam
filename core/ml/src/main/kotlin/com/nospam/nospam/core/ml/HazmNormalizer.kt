// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Faithful Kotlin port of the hazm 0.12 `Normalizer` used by the training
 * baseline (`baseline_tfidf.py`). The on-device preprocess must be byte-identical
 * to the Python pipeline's `preprocess()` — normalization divergence was flipping
 * verdicts (e.g. Persian digits surviving an ASCII-only `\d`, and ZWNJ being
 * stripped instead of inserted the way hazm does for می-verbs).
 *
 * Everything operational data-driven from [HazmResources] + the lexicon sets
 * (all exported from the training venv), so a future hazm/lexicon bump is an
 * asset regeneration, not a code rewrite. This class is designed to be loaded
 * once at classifier prewarm (off the main thread) and shared.
 */
class HazmNormalizer(
    resources: HazmResources,
    private val words: Set<String>,
    private val wordsPositive: Set<String>,
    private val verbs: Set<String>,
) {

    private val zwnj = '\u200c'

    // Python's `re` matches \d/\w/\b/\s with Unicode semantics by default; Java's
    // regex \d is ASCII-only and Kotlin 2.2 removed RegexOption.UNICODE_CHARACTER_CLASS,
    // so every compiled pattern gets the inline `(?u)` flag (UTS#18) instead.
    private val unicode = "(?u)"

    // Java `(?u)\w`/`\b` still exclude Arabic/Persian letters as word chars
    // (unlike Python 3), so hazm patterns anchored with \b/\B silently no-op for
    // Persian. Transpile the tokens to explicit assertions over Python's \w set
    // (letters + numbers + underscore) when compiling resource patterns.
    private val pythonWordChar = "[\\p{L}\\p{N}_]"

    private fun pythonBoundaries(pattern: String): String {
        var out = pattern
        out = out.replace(
            "\\B",
            "(?:(?<=$pythonWordChar)(?=$pythonWordChar)|(?<!$pythonWordChar)(?!$pythonWordChar))",
        )
        out = out.replace(
            "\\b",
            "(?:(?<=$pythonWordChar)(?!$pythonWordChar)|(?<!$pythonWordChar)(?=$pythonWordChar))",
        )
        return out
    }

    // Char maps are hoisted to HashMap once; hazm translate() is called twice per
    // normalize() and a per-char String.indexOf would dominate the whole pipeline.
    private val transliteration: Map<Char, Char> =
        charmaps(resources.translationSrc, resources.translationDst)
    private val numbers: Map<Char, Char> =
        charmaps(resources.numbersSrc, resources.numbersDst)
    private val suffixes: Set<String> = resources.suffixes.toHashSet()

    // All regexes are compiled once per instance (classifier is a singleton), never
    // per message — keeps per-message preprocess() in microsecond territory.
    private val styleRules = compileRules(resources.persianStyle)
    private val diacriticsRules = compileRules(resources.diacritics)
    private val extraSpaceRules = compileRules(resources.extraSpace)
    private val affixRules = compileRules(resources.affixSpacing)
    private val punctuationRules = compileRules(resources.punctuationSpacing)
    private val specialRules = compileRules(resources.specialChars)
    private val unicodeReplacements: List<Pair<String, String>> =
        resources.unicodeReplacements.map { it[0] to it[1] }

    private val urlRegex = Regex(
        unicode + pythonBoundaries(
            """(?:https?://[^\s]+)|(?:www\.[^\s]+)|(?:\b[a-zA-Z0-9][a-zA-Z0-9-]*(?:\.[a-zA-Z0-9-]+)+(?:/[^\s]*)?)"""
        ),
    )
    // Python 3 `\d` matches any Unicode decimal digit (Persian/Arabic/ASCII);
    // Java's `\d` is ASCII-only even with (?u), so use the Unicode Nd category.
    private val digitsRegex = Regex(unicode + "\\p{Nd}+")
    private val tokenizeRegex = Regex(unicode + resources.tokenizePattern)
    private val repeatCharsRegex = Regex(unicode + resources.repeatCharsPattern)
    private val repeatMoreRegex = Regex(unicode + resources.repeatMoreThanTwoPattern)
    private val seperateMiRegex = Regex(unicode + pythonBoundaries(resources.seperateMiPattern))

    /**
     * Baseline `preprocess()`: masks URLs and digits BEFORE hazm normalization,
     * exactly like `baseline_tfidf.py`. `\d` here is Unicode-aware (covers Persian
     * digits) — matching Python's `re`, not Java's default ASCII `\d`.
     */
    fun preprocess(text: String): String {
        var t = urlRegex.replace(text) { " URLTOKEN " }
        t = digitsRegex.replace(t) { " NUM_TOKEN " }
        return normalize(t)
    }

    /**
     * hazm `Normalizer.normalize()`. Step order is significant and mirrors
     * hazm 0.12 exactly: charmap → persian_style → persian_number → diacritics →
     * correct_spacing (extra-space, per-line tokenize+token_spacing, affix,
     * punctuation) → unicode replacements → special chars → decrease_repeated →
     * seperate_mi.
     */
    fun normalize(text: String): String {
        var t = translate(text, transliteration)
        t = apply(styleRules, t)
        t = translate(t, numbers)
        t = apply(diacriticsRules, t)

        t = apply(extraSpaceRules, t)
        val lines = t.split('\n')
        val rebuilt = StringBuilder(t.length)
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) {
                rebuilt.append(line)
            } else {
                val tokens = tokenize(line)
                rebuilt.append(tokenSpacing(tokens).joinToString(" "))
            }
            if (i < lines.lastIndex) rebuilt.append('\n')
        }
        t = rebuilt.toString()
        t = apply(affixRules, t)
        t = apply(punctuationRules, t)

        for ((old, new) in unicodeReplacements) t = t.replace(old, new)
        t = apply(specialRules, t)

        t = decreaseRepeated(t)
        t = seperateMi(t)
        return t
    }

    private fun translate(text: String, map: Map<Char, Char>): String {
        if (map.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(map[c] ?: c)
        return sb.toString()
    }

    private fun apply(rules: List<Pair<Regex, List<HazmPart>>>, text: String): String {
        if (rules.isEmpty()) return text
        var out = text
        for ((rx, parts) in rules) {
            out = rx.replace(out) { m -> expand(parts, m) }
        }
        return out
    }

    private fun expand(parts: List<HazmPart>, m: MatchResult): String {
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in parts) {
            val literal = p.l
            if (literal != null) {
                sb.append(literal)
            } else {
                val g = p.g ?: 0
                if (g in m.groupValues.indices) sb.append(m.groupValues[g])
            }
        }
        return sb.toString()
    }

    /** mirrors hazm `WordTokenizer.tokenize()` (split on punctuation/digits/dots). */
    private fun tokenize(text: String): List<String> {
        var t = text.replace('\n', ' ').replace('\t', ' ')
        t = tokenizeRegex.replace(t) { m -> " " + (m.groupValues.getOrNull(1) ?: "") + " " }
        return t.split(' ').filter { it.isNotEmpty() }
    }

    /** mirrors hazm `Normalizer._token_spacing()` (lexicon-gated ZWNJ joining). */
    private fun tokenSpacing(tokens: List<String>): List<String> {
        if (words.isEmpty() && wordsPositive.isEmpty() && verbs.isEmpty()) return tokens
        val result = mutableListOf<String>()
        for ((i, token) in tokens.withIndex()) {
            var joined = false
            if (result.isNotEmpty()) {
                val tokenPair = result[result.lastIndex] + zwnj + token
                if (words.isNotEmpty() &&
                    ((verbs.isNotEmpty() && tokenPair in verbs) || tokenPair in wordsPositive)
                ) {
                    joined = true
                    if (i < tokens.lastIndex &&
                        verbs.isNotEmpty() &&
                        (token + "_" + tokens[i + 1]) in verbs
                    ) {
                        joined = false
                    }
                } else if (words.isNotEmpty() && token in suffixes && result[result.lastIndex] in words) {
                    joined = true
                }
            }
            if (joined) result[result.lastIndex] = result[result.lastIndex] + zwnj + token
            else result.add(token)
        }
        return result
    }

    /** mirrors hazm `Normalizer._decrease_repeated_chars()` (lexicon-gated). */
    private fun decreaseRepeated(text: String): String {
        if (words.isEmpty() || repeatCharsRegex.pattern.isEmpty()) return text
        val matches = repeatCharsRegex.findAll(text).toList()
        if (matches.isEmpty()) return text
        var out = text
        for (k in matches.indices.reversed()) {
            val match = matches[k]
            val word = match.value
            if (word in words) continue
            val noRepeat = repeatMoreRegex.replace(word) { m -> m.groupValues[1] }
            val twoRepeat = repeatMoreRegex.replace(word) { m -> m.groupValues[1] + m.groupValues[1] }
            val noInWords = noRepeat in words
            val twoInWords = twoRepeat in words
            val replacement = when {
                noInWords != twoInWords -> if (noInWords) noRepeat else twoRepeat
                else -> twoRepeat
            }
            val start = match.range.first
            val end = match.range.last
            out = out.substring(0, start) + replacement + out.substring(end + 1)
        }
        return out
    }

    /** mirrors hazm `Normalizer._seperate_mi()` (verbs-lexicon-gated ZWNJ insert). */
    private fun seperateMi(text: String): String {
        if (verbs.isEmpty() || seperateMiRegex.pattern.isEmpty()) return text
        return seperateMiRegex.replace(text) { m ->
            val group = m.value
            val candidate = when {
                group.startsWith("نمی") -> "نمی" + zwnj + group.substring(3)
                group.startsWith("می") -> "می" + zwnj + group.substring(2)
                else -> group
            }
            if (candidate in verbs) candidate else group
        }
    }

    private fun compileRules(rules: List<HazmRule>): List<Pair<Regex, List<HazmPart>>> =
        rules.map { Regex(unicode + pythonBoundaries(it.p)) to it.r }

    private fun charmaps(src: String, dst: String): Map<Char, Char> {
        if (src.isEmpty() || src.length != dst.length) return emptyMap()
        val map = HashMap<Char, Char>(src.length)
        for (i in src.indices) map[src[i]] = dst[i]
        return map
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads from Android assets (classifier prewarm path). */
        fun fromAssets(context: Context): HazmNormalizer {
            val resourcesJson = context.assets.open("hazm_normalizer.json").bufferedReader().use { it.readText() }
            return buildFrom(
                resourcesJson,
                loadWordSet { context.assets.open("hazm_words.txt").bufferedReader() },
                loadWordSet { context.assets.open("hazm_words_positive.txt").bufferedReader() },
                loadWordSet { context.assets.open("hazm_verbs.txt").bufferedReader() },
            )
        }

        /** Loads from a directory containing the four exported files (JVM tests). */
        fun fromFiles(dir: File): HazmNormalizer {
            val resourcesJson = File(dir, "hazm_normalizer.json").readText()
            return buildFrom(
                resourcesJson,
                loadWordSet { File(dir, "hazm_words.txt").bufferedReader() },
                loadWordSet { File(dir, "hazm_words_positive.txt").bufferedReader() },
                loadWordSet { File(dir, "hazm_verbs.txt").bufferedReader() },
            )
        }

        /** Identity-ish normalizer for tests that don't exercise hazm behavior. */
        fun empty(): HazmNormalizer = HazmNormalizer(
            HazmResources(
                translationSrc = "", translationDst = "",
                numbersSrc = "", numbersDst = "",
                suffixes = emptyList(),
                persianStyle = emptyList(), diacritics = emptyList(),
                extraSpace = emptyList(), affixSpacing = emptyList(),
                punctuationSpacing = emptyList(), specialChars = emptyList(),
                unicodeReplacements = emptyList(),
                tokenizePattern = "(\\S+)",
                repeatCharsPattern = "",
                repeatMoreThanTwoPattern = "",
                seperateMiPattern = "",
            ),
            emptySet(), emptySet(), emptySet(),
        )

        private fun buildFrom(
            resourcesJson: String,
            words: Set<String>,
            wordsPositive: Set<String>,
            verbs: Set<String>,
        ): HazmNormalizer = HazmNormalizer(json.decodeFromString<HazmResources>(resourcesJson), words, wordsPositive, verbs)

        private inline fun loadWordSet(open: () -> java.io.BufferedReader): Set<String> {
            val set = HashSet<String>()
            open().use { reader -> reader.forEachLine { line -> if (line.isNotEmpty()) set.add(line) } }
            return set
        }
    }
}
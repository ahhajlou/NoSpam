package com.nospam.nospam.core.ml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One part of a serialized regex replacement: either a literal string (`l`) or a
 * reference to a captured group (`g`). The Python hazm sources do `re.sub(p, r, s)`
 * with `\1`-style replacements; Java/Kotlin need `$1`/lambda replacements, so the
 * exporter pre-parses each template into a portable part list.
 */
@Serializable
data class HazmPart(
    val l: String? = null,
    val g: Int? = null,
)

/** A single hazm regex rule: pattern `p` + replacement parts `r`. */
@Serializable
data class HazmRule(
    val p: String,
    val r: List<HazmPart> = emptyList(),
)

/**
 * Serialized form of the hazm 0.12 `Normalizer` constants (see
 * `hazm/constants.py`) plus the tokenizer/lexicon patterns the normalizer uses.
 * Exported by `export_android_assets.py` from the training venv; the Kotlin
 * [HazmNormalizer] consumes exactly this data and nothing else.
 */
@Serializable
data class HazmResources(
    @SerialName("translation_src") val translationSrc: String,
    @SerialName("translation_dst") val translationDst: String,
    @SerialName("numbers_src") val numbersSrc: String,
    @SerialName("numbers_dst") val numbersDst: String,
    val suffixes: List<String>,
    @SerialName("persian_style") val persianStyle: List<HazmRule>,
    val diacritics: List<HazmRule>,
    @SerialName("extra_space") val extraSpace: List<HazmRule>,
    @SerialName("affix_spacing") val affixSpacing: List<HazmRule>,
    @SerialName("punctuation_spacing") val punctuationSpacing: List<HazmRule>,
    @SerialName("special_chars") val specialChars: List<HazmRule>,
    @SerialName("unicode_replacements") val unicodeReplacements: List<List<String>>,
    @SerialName("tokenize_pattern") val tokenizePattern: String,
    @SerialName("repeat_chars_pattern") val repeatCharsPattern: String,
    @SerialName("repeat_more_than_two_pattern") val repeatMoreThanTwoPattern: String,
    @SerialName("seperate_mi_pattern") val seperateMiPattern: String,
)
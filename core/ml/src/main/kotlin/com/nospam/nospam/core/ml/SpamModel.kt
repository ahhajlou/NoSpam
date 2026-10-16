// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import kotlinx.serialization.Serializable

/**
 * Serialized TfidfVectorizer + LinearSVC pipeline, exported with the transform
 * parameters so on-device scoring reproduces sklearn's
 * `decision_function()` exactly (sublinear_tf → idf → L2 row norm → coef dot).
 * `idf` aligns 1:1 with `vocab`/`weights`; the nullable transform fields carry
 * defaults so older (pre-idf) exported models still deserialize.
 */
@Serializable
data class SpamModel(
    val vocab: Map<String, Int>,
    val weights: List<Double>,
    val bias: Double,
    val classes: List<String>,
    val idf: List<Double>? = null,
    val ngramRange: List<Int>? = null,
    val sublinearTf: Boolean? = null,
    val norm: String? = null,
    val lowercase: Boolean? = null,
)
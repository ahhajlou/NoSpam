// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import kotlinx.serialization.Serializable

/**
 * Serialized TfidfVectorizer + LinearSVC pipeline, written by persian-sms-spam's
 * `make export-android`, with the transform parameters so on-device scoring
 * reproduces sklearn's `decision_function()` exactly (sublinear_tf → idf → L2
 * row norm → coef dot). `idf` aligns 1:1 with `vocab`/`weights`; a missing
 * transform field means sklearn's default.
 */
@Serializable
data class SpamModel(
    val vocab: Map<String, Int>,
    val weights: List<Double>,
    val bias: Double,
    val classes: List<String>,
    val idf: List<Double>? = null,
    val sublinearTf: Boolean? = null,
    val norm: String? = null,
    /**
     * The text pipeline the model was trained with. It must equal
     * [NativeCore.pipeline], or the n-grams would not mean what they meant in
     * training; a model without one is from before the Rust core.
     */
    val pipeline: Pipeline,
    /**
     * The persian-sms-spam commit the model was exported from, for tracing it
     * back. Never checked: a model trained on a fork with the same pipeline
     * works.
     */
    val coreGitSha: String? = null,
)

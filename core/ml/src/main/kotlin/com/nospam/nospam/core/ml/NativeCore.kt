// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import kotlinx.serialization.Serializable

/**
 * The spam filter's text processing: the Rust core of persian-sms-spam
 * (`third_party/persian-sms-spam`, crate `core/spam-core-jni`), which this module's
 * Gradle build compiles into `libspam_core_jni.so`. Training runs the same code, so
 * the phone turns an SMS into exactly the model input training did.
 *
 * The JNI function names encode this package and class: renaming or moving
 * `NativeCore` means renaming every `Java_com_nospam_nospam_core_ml_NativeCore_*`
 * function in that crate.
 *
 * A missing library fails the first use with `UnsatisfiedLinkError`, an `Error`
 * rather than an `Exception`; the classifier's callers catch `Throwable`.
 */
object NativeCore {
    init {
        System.loadLibrary("spam_core_jni")
    }

    /** The core's `PIPELINE_VERSION`, bumped on any change to its output. */
    @JvmStatic external fun pipelineVersion(): Int

    /** The normalizer compiled into the app: always "rules" (hazm's rules, no word lists). */
    @JvmStatic external fun normalizerMode(): String

    /**
     * Raw SMS to the model's features: the whole pipeline, then lowercase and
     * `char_wb` 2-4-grams, in order and with repeats.
     */
    @JvmStatic external fun ngrams(text: String): Array<String>

    /** Raw SMS to the output of each step, in the order of [PipelineStages]. */
    @JvmStatic external fun stages(text: String): Array<String>

    /** The pipeline this core implements; a model must have been trained with it. */
    fun pipeline(): Pipeline = Pipeline(pipelineVersion(), normalizerMode())

    /**
     * Compiles the core's rules now instead of on the first message. The core
     * compiles them once per process, on first use, whatever the text: about
     * 0.28s on a Galaxy A26, which the first classification otherwise pays.
     */
    fun warmUp() {
        ngrams("")
    }

    fun pipelineStages(text: String): PipelineStages {
        val (anonymized, urlsMasked, numbersMasked, normalized) = stages(text)
        return PipelineStages(anonymized, urlsMasked, numbersMasked, normalized)
    }
}

/** A text pipeline: the core's `PIPELINE_VERSION` and normalizer mode. */
@Serializable
data class Pipeline(val version: Int, val normalizer: String)

/** What each step of the pipeline made of one message. */
data class PipelineStages(
    val anonymized: String,
    val urlsMasked: String,
    val numbersMasked: String,
    /** The text the n-grams are taken from. */
    val normalized: String,
)

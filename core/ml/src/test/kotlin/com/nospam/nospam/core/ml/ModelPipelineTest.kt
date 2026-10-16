// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A model is used only with the text pipeline it was trained with: the same
 * `PIPELINE_VERSION` and normalizer as the core compiled into the app. The commit it was
 * exported from is not checked.
 */
class ModelPipelineTest {

    private val shippedJson = File("src/main/assets/spam_model.json").readText()

    private fun withPipeline(pipeline: JsonObject?): String {
        val model = Json.parseToJsonElement(shippedJson).jsonObject.toMutableMap()
        if (pipeline == null) model.remove("pipeline") else model["pipeline"] = pipeline
        return JsonObject(model).toString()
    }

    private fun pipeline(version: Int, normalizer: String) =
        JsonObject(mapOf("version" to JsonPrimitive(version), "normalizer" to JsonPrimitive(normalizer)))

    @Test fun `the shipped model was trained with the core's pipeline`() {
        val classifier = TfidfSpamClassifier.fromJson(shippedJson)
        assertEquals(NativeCore.pipeline(), classifier.pipeline)
        assertEquals(40, classifier.coreGitSha?.length)
    }

    @Test fun `a model from another pipeline version is refused`() {
        val core = NativeCore.pipeline()
        assertThrows(IllegalStateException::class.java) {
            TfidfSpamClassifier.fromJson(withPipeline(pipeline(core.version + 1, core.normalizer)))
        }
    }

    @Test fun `a model trained with the full normalizer is refused`() {
        assertThrows(IllegalStateException::class.java) {
            TfidfSpamClassifier.fromJson(withPipeline(pipeline(NativeCore.pipelineVersion(), "full")))
        }
    }

    @Test fun `a model without a pipeline is refused`() {
        assertThrows(SerializationException::class.java) {
            TfidfSpamClassifier.fromJson(withPipeline(null))
        }
    }

    @Test fun `a model from another commit with the same pipeline is used`() {
        val model = Json.parseToJsonElement(shippedJson).jsonObject.toMutableMap()
        model["coreGitSha"] = JsonPrimitive("0".repeat(40))
        assertEquals("0".repeat(40), TfidfSpamClassifier.fromJson(JsonObject(model).toString()).coreGitSha)
    }
}

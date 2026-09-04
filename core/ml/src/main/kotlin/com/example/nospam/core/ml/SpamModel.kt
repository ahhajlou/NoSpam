package com.example.nospam.core.ml

import kotlinx.serialization.Serializable

@Serializable
data class SpamModel(
    val vocab: Map<String, Int>,
    val weights: List<Double>,
    val bias: Double,
    val classes: List<String>
)

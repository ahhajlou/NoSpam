package com.example.nospam.core.ml

import com.example.nospam.core.model.RawMessage
import com.example.nospam.core.model.SpamVerdict

interface SpamClassifier {
    suspend fun classify(message: RawMessage): SpamVerdict
    suspend fun classifyText(text: String): SpamVerdict
}

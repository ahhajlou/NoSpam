// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamVerdict

interface SpamClassifier {
    suspend fun classify(message: RawMessage): SpamVerdict
    suspend fun classifyText(text: String): SpamVerdict
}

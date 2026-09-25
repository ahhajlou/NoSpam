// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.ml

import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.SpamVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [SpamClassifier] that builds the real one on first use, on [dispatcher].
 *
 * Loading the model parses a 1.2 MB JSON asset, measured at ~540ms on a Galaxy
 * A26. Objects that only hold a classifier took it as a constructor argument,
 * so building them loaded the model, or waited for a load already under way,
 * on whatever thread built them. A cold-start trace showed the main thread
 * blocked for 427ms of the inbox's first composition this way: creating the
 * inbox's ViewModel built the history-scan use case, which asked for the
 * classifier while the startup warm-up held its lock. Holding this instead
 * costs nothing until a message is classified, and that happens off the main
 * thread.
 */
class DeferredSpamClassifier(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    load: () -> SpamClassifier,
) : SpamClassifier {
    private val delegate = lazy(load)

    private suspend fun resolved(): SpamClassifier =
        if (delegate.isInitialized()) delegate.value else withContext(dispatcher) { delegate.value }

    override suspend fun classify(message: RawMessage): SpamVerdict = resolved().classify(message)

    override suspend fun classifyText(text: String): SpamVerdict = resolved().classifyText(text)
}

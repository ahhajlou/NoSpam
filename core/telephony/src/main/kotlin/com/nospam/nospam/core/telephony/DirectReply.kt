// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context

/** Told when a reply typed into a message notification has been sent. */
fun interface DirectReplyListener {
    fun onReplySent(context: Context, threadId: Long, text: String)
}

/**
 * What happens to a message notification once its inline reply is sent. Android
 * 15+ marks a notification the user replied to and ignores the app's cancel()
 * until the app posts it again, so `:app` installs a listener in
 * `Application.onCreate` that reposts it with the reply added (core:telephony
 * may not depend on core:notifications). The default cancels, which is right
 * before Android 15 and is what tests get.
 */
object DirectReplyRegistry {
    @Volatile
    var listener: DirectReplyListener = DirectReplyListener { context, threadId, _ ->
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(threadId.hashCode())
        }
    }
}

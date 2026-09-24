// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.testing

import com.nospam.nospam.core.notifications.MessageSoundPlayer

/** Counts the sounds asked for instead of playing them. */
class FakeMessageSoundPlayer : MessageSoundPlayer {
    var sentCount = 0
        private set
    var receivedCount = 0
        private set

    override fun playSent() {
        sentCount++
    }

    override fun playReceived() {
        receivedCount++
    }
}

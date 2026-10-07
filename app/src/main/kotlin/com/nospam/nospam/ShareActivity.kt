// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

/**
 * Text shared from another app (`SEND`), in a task of its own
 * (`documentLaunchMode="always"`), as Google Messages, AOSP Messaging and
 * Signal do: the recipient picker and then the conversation open there, and
 * the app's main task is left as it was. Leaving the picker returns to the app
 * that shared.
 *
 * Shares used to reach the running [MainActivity] through `onNewIntent`, which
 * broke after process death: the activity came back with its saved back
 * stack, the inbox, and the share was lost. A share's own task restores only
 * its own picker or conversation, which already hold the text.
 */
class ShareActivity : MainActivity() {
    override val leavesAtStart = true
}

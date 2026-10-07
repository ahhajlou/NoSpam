// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nospam.nospam.navigation.launchIntentFor
import com.nospam.nospam.navigation.toLaunchTarget

/**
 * Another app's "send SMS to" (`SENDTO`): opens that conversation in a fresh
 * task, as Google Messages and AOSP Messaging do through an activity of the
 * same name. It used to reach the running [MainActivity] through
 * `onNewIntent`, which broke after process death: the activity came back with
 * its saved back stack, the inbox, and the request was lost.
 *
 * Draws nothing and finishes in [onCreate]. Only the parsed recipient and text
 * go on to [MainActivity], never the caller's intent (CLAUDE.md §12).
 */
class LaunchConversationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.takeIf { it.action == Intent.ACTION_SENDTO }?.toLaunchTarget()
        val open = if (target != null) {
            launchIntentFor(this, MainActivity::class.java, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            // Nothing to open: show the app as it was, as the launcher would.
            packageManager.getLaunchIntentForPackage(packageName)
        }
        open?.let(::startActivity)
        finish()
    }
}

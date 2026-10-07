// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.navigation.LaunchTarget
import com.nospam.nospam.navigation.toLaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What other apps send arrives in its own activity, never in a running
 * [MainActivity], where it was lost after process death: the restored back
 * stack (the inbox) won. "Send SMS to" opens the conversation in a fresh task;
 * a share opens in a task of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalLaunchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun receivers(intent: Intent, flags: Int = PackageManager.MATCH_DEFAULT_ONLY): List<String> =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), flags)
            .map { it.activityInfo.name }

    private fun sendTo(uri: String) = Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).addCategory(Intent.CATEGORY_DEFAULT)

    private fun share(text: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    @Test fun `send SMS to reaches only the launch activity, for every scheme`() {
        for (scheme in listOf("sms", "smsto", "mms", "mmsto")) {
            assertEquals(scheme, listOf(LaunchConversationActivity::class.java.name), receivers(sendTo("$scheme:+15551234")))
        }
    }

    @Test fun `a share reaches only the share activity`() {
        assertEquals(listOf(ShareActivity::class.java.name), receivers(share("hi")))
    }

    @Test fun `the launcher still opens the main activity`() {
        // A launcher filter has no DEFAULT category, so no MATCH_DEFAULT_ONLY.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertEquals(listOf(MainActivity::class.java.name), receivers(launcher, flags = 0))
    }

    // Started in the app's own task, the trampoline brought that task to the
    // front first, and with the app running its inbox showed for ~0.7s before
    // the conversation. In a task of its own (as AOSP Messaging's is), the
    // app's task only appears once it holds the conversation.
    @Test fun `send SMS to starts in a task of its own`() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, LaunchConversationActivity::class.java), 0,
        )
        assertEquals(android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS, info.documentLaunchMode)
    }

    private fun launch(intent: Intent): Pair<LaunchConversationActivity, Intent?> {
        val activity = Robolectric.buildActivity(LaunchConversationActivity::class.java, intent).create().get()
        return activity to shadowOf(activity).nextStartedActivity
    }

    @Test fun `send SMS to opens the conversation in a fresh task, then finishes`() {
        val (activity, started) = launch(sendTo("smsto:+15551234").putExtra("sms_body", "hello"))
        assertNotNull(started)
        started!!
        assertEquals(MainActivity::class.java.name, started.component?.className)
        val fresh = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(fresh, started.flags and fresh)
        assertEquals(LaunchTarget.Compose("+15551234", "hello"), started.toLaunchTarget())
        assertTrue(activity.isFinishing)
    }

    @Test fun `only the parsed request goes on, not the caller's extras`() {
        val (_, started) = launch(sendTo("smsto:+15551234").putExtra("unexpected", "payload"))
        assertFalse(started!!.hasExtra("unexpected"))
    }

    @Test fun `a request with no recipient opens the app as it was, without clearing it`() {
        val (activity, started) = launch(sendTo("smsto:"))
        assertNotNull(started)
        assertEquals(0, started!!.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(null, started.toLaunchTarget())
        assertTrue(activity.isFinishing)
    }

    @Test fun `an action other than send SMS to is not acted on`() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:+15551234")).putExtra("thread_id", 7L)
        val (activity, started) = launch(view)
        assertEquals(null, started?.toLaunchTarget())
        assertTrue(activity.isFinishing)
    }
}

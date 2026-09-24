// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nospam.nospam.core.model.NotificationDecision

/** The in-app message sounds. Never throws; plays nothing when the phone is silenced. */
interface MessageSoundPlayer {
    /** A message the user sent was handed to the radio. */
    fun playSent()

    /** A message arrived in the conversation the user is looking at. */
    fun playReceived()
}

/**
 * [MessageSoundPlayer] from Android's own sounds, so nothing is bundled:
 * received is the phone's default notification sound; sent is a short system
 * confirmation tone, because Android has no stock "message sent" sound and its
 * click sound follows the separate "touch sounds" setting, which many phones
 * have off. Both stay quiet unless the ringer is in normal mode.
 */
class SystemMessageSoundPlayer(context: Context) : MessageSoundPlayer {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private fun audible(): Boolean = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    override fun playSent() {
        if (!audible()) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, SENT_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, SENT_DURATION_MS)
            main.postDelayed({ tone.release() }, SENT_DURATION_MS + RELEASE_MARGIN_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Sent sound failed", e)
        }
    }

    override fun playReceived() {
        if (!audible()) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
        } catch (e: Exception) {
            Log.w(TAG, "Received sound failed", e)
        }
    }

    private companion object {
        const val TAG = "MessageSounds"
        const val SENT_VOLUME = 60
        const val SENT_DURATION_MS = 150
        const val RELEASE_MARGIN_MS = 100L
    }
}

/** What to do about an incoming message. */
enum class IncomingAlert {
    /** Post the usual notification. */
    NOTIFY,

    /** The user is looking at this conversation: no notification, the in-app sound. */
    IN_APP_SOUND,

    /** Nothing: a silenced message, or one on screen with message sounds off. */
    NONE,
}

/**
 * How an incoming message announces itself. Only a message the spam policy
 * lets notify ([NotificationDecision.NORMAL]) does anything. When its
 * conversation is the one on screen ([visibleThreadId]) the user is already
 * reading it, so there is no notification, only the in-app sound if
 * [soundsEnabled], as Google Messages does.
 */
fun incomingAlert(
    decision: NotificationDecision,
    threadId: Long,
    visibleThreadId: Long?,
    soundsEnabled: Boolean,
): IncomingAlert = when {
    decision != NotificationDecision.NORMAL -> IncomingAlert.NONE
    visibleThreadId != threadId -> IncomingAlert.NOTIFY
    soundsEnabled -> IncomingAlert.IN_APP_SOUND
    else -> IncomingAlert.NONE
}

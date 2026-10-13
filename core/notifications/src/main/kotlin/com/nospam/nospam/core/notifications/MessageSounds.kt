// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.nospam.nospam.core.model.NotificationDecision
import java.util.concurrent.Executor

/** The in-app message sounds. Never throws; plays nothing when the phone is silenced. */
interface MessageSoundPlayer {
    /**
     * Loads the sounds so the first one plays on time; called when a conversation
     * opens. Only the first call does anything, and it does not block.
     */
    fun prepare()

    /** A message the user sent was handed to the radio. */
    fun playSent()

    /** A message arrived in the conversation the user is looking at. */
    fun playReceived()
}

/**
 * [MessageSoundPlayer] for the app's own sounds, `res/raw/message_sent.ogg` and
 * `res/raw/message_received.ogg`. To change a sound, replace its file with
 * `tools/message-sound.sh` (docs/SOUNDS.md); nothing here names a particular sound.
 *
 * Android has no stock "message sent" sound, so messaging apps ship their own,
 * as Google Messages does. They play through a [SoundPool], which decodes them
 * once so they start without delay, at the notification volume, and only when
 * the ringer is in normal mode. A burst of incoming messages plays one sound,
 * not one each ([receivedSoundDue]).
 */
class BundledMessageSoundPlayer(
    context: Context,
    private val loader: Executor = Executor { Thread(it, TAG).start() },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : MessageSoundPlayer {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    @VisibleForTesting
    @Volatile
    internal var pool: SoundPool? = null
    @Volatile private var sentId = 0
    @Volatile private var receivedId = 0
    private var started = false
    private var lastReceivedAt: Long? = null

    override fun prepare() {
        synchronized(this) {
            if (started) return
            started = true
        }
        loader.execute {
            try {
                val sounds = SoundPool.Builder()
                    .setMaxStreams(MAX_STREAMS)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                sentId = sounds.load(appContext, R.raw.message_sent, 1)
                receivedId = sounds.load(appContext, R.raw.message_received, 1)
                pool = sounds
            } catch (e: Exception) {
                Log.w(TAG, "Loading message sounds failed", e)
            }
        }
    }

    override fun playSent() = play(sentId, SENT_VOLUME)

    override fun playReceived() {
        synchronized(this) {
            val time = now()
            if (!receivedSoundDue(time, lastReceivedAt)) return
            lastReceivedAt = time
        }
        play(receivedId, RECEIVED_VOLUME)
    }

    private fun play(soundId: Int, volume: Float) {
        // Not loaded yet (the first sound right after start): skip it rather than wait.
        val sounds = pool ?: return prepare()
        if (soundId == 0 || audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            sounds.play(soundId, volume, volume, 1, 0, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "Message sound failed", e)
        }
    }

    private companion object {
        const val TAG = "MessageSounds"
        const val MAX_STREAMS = 2

        /**
         * Playback volume of each sound, 0 to 1, on top of the notification volume.
         * The files are already leveled by tools/message-sound.sh, so these stay at
         * 1 unless one sound should sit quieter than the other.
         */
        const val SENT_VOLUME = 1f
        const val RECEIVED_VOLUME = 1f
    }
}

/** The shortest gap between two received sounds; closer messages play only the first. */
const val RECEIVED_SOUND_GAP_MS = 2_000L

/**
 * Whether a message received at [now] plays its sound, given when the last
 * received sound played ([lastPlayedAt], null if none). Times are from one
 * monotonic clock.
 */
fun receivedSoundDue(now: Long, lastPlayedAt: Long?): Boolean =
    lastPlayedAt == null || now - lastPlayedAt >= RECEIVED_SOUND_GAP_MS

/** What to do about an incoming message. */
enum class IncomingAlert {
    /** Post the usual notification. */
    NOTIFY,

    /**
     * Post a quiet notification on the low-importance spam channel, labeled
     * as suspected spam: a message that looks like spam but stays in the inbox,
     * when the user asked to be told about those.
     */
    NOTIFY_QUIET,

    /** The user is looking at this conversation: no notification, the in-app sound. */
    IN_APP_SOUND,

    /** Nothing: a silenced message, or one on screen with message sounds off. */
    NONE,
}

/**
 * How an incoming message announces itself. A message the spam policy lets
 * notify ([NotificationDecision.NORMAL]) posts the usual notification. One it
 * silences while leaving it in the inbox ([NotificationDecision.SILENT], spam
 * that looks suspicious but is not hidden) posts a quiet one only when the user
 * turned on [notifySuspectedSpam]. A message routed to Spam
 * ([NotificationDecision.NONE]) never announces itself.
 *
 * When the conversation is the one on screen ([visibleThreadId]) the user is
 * already reading it, so there is no notification; a normal message plays the
 * in-app sound if [soundsEnabled], as Google Messages does, and a suspected one
 * plays nothing.
 */
fun incomingAlert(
    decision: NotificationDecision,
    threadId: Long,
    visibleThreadId: Long?,
    soundsEnabled: Boolean,
    notifySuspectedSpam: Boolean = false,
): IncomingAlert = when {
    decision == NotificationDecision.SILENT ->
        if (notifySuspectedSpam && visibleThreadId != threadId) IncomingAlert.NOTIFY_QUIET else IncomingAlert.NONE
    decision != NotificationDecision.NORMAL -> IncomingAlert.NONE
    visibleThreadId != threadId -> IncomingAlert.NOTIFY
    soundsEnabled -> IncomingAlert.IN_APP_SOUND
    else -> IncomingAlert.NONE
}

/** How long after a message arrives a late verdict may still announce it. */
const val LATE_ALERT_WINDOW_MS = 2 * 60_000L

/**
 * What to do when a message's verdict arrives after the message was announced
 * ([atArrival]), now that the verdict asks for [late]. It announces only a
 * message that announced nothing on arrival, which is a message from a sender
 * in Spam that turned out to be real, and only within [LATE_ALERT_WINDOW_MS]
 * of its arrival ([elapsedMillis]): a heads-up for a message minutes old is
 * noise. A notification already posted is never changed.
 */
fun lateAlert(atArrival: IncomingAlert, late: IncomingAlert, elapsedMillis: Long): IncomingAlert =
    if (atArrival == IncomingAlert.NONE && elapsedMillis <= LATE_ALERT_WINDOW_MS) late else IncomingAlert.NONE

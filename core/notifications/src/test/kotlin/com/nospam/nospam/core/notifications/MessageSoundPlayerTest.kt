// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * [BundledMessageSoundPlayer] against Robolectric's SoundPool: which bundled
 * sound plays, the ringer gate, loading once, and one received sound per burst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageSoundPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var time = 10_000L
    private var loads = 0
    private val inline = Executor { loads++; it.run() }
    private lateinit var player: BundledMessageSoundPlayer

    @Before fun setUp() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        player = BundledMessageSoundPlayer(context, loader = inline, now = { time })
    }

    private fun played(resId: Int) = shadowOf(player.pool!!).wasResourcePlayed(resId)

    @Test fun `sent plays the bundled sent sound and nothing else`() {
        player.prepare()
        player.playSent()
        assertTrue(played(R.raw.message_sent))
        assertFalse(played(R.raw.message_received))
    }

    @Test fun `received plays the bundled received sound and nothing else`() {
        player.prepare()
        player.playReceived()
        assertTrue(played(R.raw.message_received))
        assertFalse(played(R.raw.message_sent))
    }

    @Test fun `nothing plays on vibrate or silent`() {
        player.prepare()
        for (mode in listOf(AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)) {
            audio.ringerMode = mode
            player.playSent()
            time += RECEIVED_SOUND_GAP_MS
            player.playReceived()
        }
        assertFalse(played(R.raw.message_sent))
        assertFalse(played(R.raw.message_received))
    }

    @Test fun `prepare loads the sounds once however often it is called`() {
        repeat(3) { player.prepare() }
        assertEquals(1, loads)
    }

    @Test fun `a sound asked for before loading is skipped and starts the load`() {
        player.playSent()
        assertEquals(1, loads)
        assertFalse(played(R.raw.message_sent))
        player.playSent()
        assertTrue(played(R.raw.message_sent))
    }

    @Test fun `a burst of received messages plays one sound`() {
        player.prepare()
        player.playReceived()
        assertTrue(played(R.raw.message_received))
        shadowOf(player.pool!!).clearPlayed()
        time += RECEIVED_SOUND_GAP_MS - 1
        player.playReceived()
        assertFalse(played(R.raw.message_received))
        time += 1
        player.playReceived()
        assertTrue(played(R.raw.message_received))
    }

    @Test fun `received sound gap rule`() {
        assertTrue(receivedSoundDue(now = 5_000, lastPlayedAt = null))
        assertFalse(receivedSoundDue(now = 5_000, lastPlayedAt = 5_000))
        assertFalse(receivedSoundDue(now = 5_000 + RECEIVED_SOUND_GAP_MS - 1, lastPlayedAt = 5_000))
        assertTrue(receivedSoundDue(now = 5_000 + RECEIVED_SOUND_GAP_MS, lastPlayedAt = 5_000))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The notification's copy of [Avatar]: the inbox's color, in a circle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarBitmapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun containerOf(key: String, dark: Boolean): Int {
        val palette = if (dark) AvatarPaletteDark else AvatarPaletteLight
        return palette[avatarPaletteIndex(avatarColorKey(key), palette.size)].first.toArgb()
    }

    @Test fun `it is the inbox avatar's color, by the same key`() {
        // "+1..." and the bare digits are one conversation, so one color.
        val bitmap = avatarBitmap(context, "Sara", "+15551110002", 64, dark = false)
        assertEquals(containerOf("5551110002", dark = false), bitmap.getPixel(32, 6))
    }

    @Test fun `dark mode uses the dark palette`() {
        val bitmap = avatarBitmap(context, null, "5559871234", 64, dark = true)
        assertEquals(containerOf("5559871234", dark = true), bitmap.getPixel(32, 6))
    }

    @Test fun `it is a circle, so a launcher does not show a square`() {
        val bitmap = avatarBitmap(context, "MCI", "MCI", 64, dark = false)
        assertEquals(64, bitmap.width)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
    }

    @Test fun `a photo is cropped to a circle of the requested size`() {
        val photo = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val avatar = circleAvatarBitmap(photo, 48)
        assertEquals(48, avatar.width)
        assertEquals(48, avatar.height)
        assertEquals(Color.RED, avatar.getPixel(24, 24))
        assertEquals(Color.TRANSPARENT, avatar.getPixel(0, 0))
    }
}

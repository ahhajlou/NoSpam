// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.nospam.nospam.core.designsystem.R

/**
 * [Avatar] drawn into a bitmap, for what Compose does not draw: a message
 * notification and its conversation shortcut. The same color, letter or person
 * icon as the inbox, so a sender looks the same in both. Without it Android
 * draws an empty circle for a sender with no photo.
 *
 * [dark] picks the palette; it should follow the system's night mode, which is
 * what draws notifications, not the app's.
 */
fun avatarBitmap(context: Context, name: String?, colorKey: String, sizePx: Int, dark: Boolean): Bitmap {
    val palette = if (dark) AvatarPaletteDark else AvatarPaletteLight
    val (container, content) = palette[avatarPaletteIndex(avatarColorKey(colorKey), palette.size)]
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val half = sizePx / 2f
    canvas.drawCircle(half, half, half, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = container.toArgb() })
    val initial = avatarInitial(name)
    if (initial != null) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = content.toArgb()
            textSize = sizePx * 0.45f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        // Centred on the glyph box, not the baseline.
        val baseline = half - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, half, baseline, paint)
    } else {
        ContextCompat.getDrawable(context, R.drawable.ic_avatar_person)?.mutate()?.let { icon ->
            val inset = (sizePx * 0.2f).toInt()
            icon.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            icon.setTint(content.toArgb())
            icon.draw(canvas)
        }
    }
    return bitmap
}

/**
 * [photo] cropped to a circle of [sizePx], as [Avatar] shows it. A launcher
 * draws a shortcut's bitmap as it is, so a square photo would show square.
 */
fun circleAvatarBitmap(photo: Bitmap, sizePx: Int): Bitmap {
    val bitmap = createBitmap(sizePx, sizePx)
    val scale = sizePx.toFloat() / minOf(photo.width, photo.height)
    // Centre crop: scale the shorter side to fit, centre the longer one.
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate((sizePx - photo.width * scale) / 2f, (sizePx - photo.height * scale) / 2f)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
    }
    val half = sizePx / 2f
    Canvas(bitmap).drawCircle(half, half, half, paint)
    return bitmap
}

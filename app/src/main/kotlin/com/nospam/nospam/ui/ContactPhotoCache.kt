// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nospam.nospam.core.designsystem.component.ContactPhotoLoader
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contact photos for avatars, decoded at the size they are drawn and kept in
 * memory for the life of the process, so scrolling the inbox reads each photo
 * once. A photo that could not be loaded is remembered as missing too, so a
 * contact without one is not queried on every scroll past. The price is that a
 * photo changed in Contacts shows up after the next app start.
 */
class ContactPhotoCache(
    private val telephony: TelephonyDataSource,
    private val decode: (bytes: ByteArray, sizePx: Int) -> ImageBitmap? = ::decodeForAvatar,
    maxEntries: Int = 128,
) : ContactPhotoLoader {
    private val photos = LruCache<String, ImageBitmap>(maxEntries)
    private val misses = LruCache<String, Unit>(maxEntries * 4)

    override suspend fun load(photoUri: String, sizePx: Int): ImageBitmap? {
        val key = "$sizePx|$photoUri"
        photos.get(key)?.let { return it }
        if (misses.get(key) != null) return null
        val photo = withContext(Dispatchers.Default) {
            runCatching { telephony.loadContactPhoto(photoUri)?.let { decode(it, sizePx) } }.getOrNull()
        }
        if (photo != null) photos.put(key, photo) else misses.put(key, Unit)
        return photo
    }
}

/**
 * The power-of-two `inSampleSize` that shrinks a [width] x [height] image as
 * far as possible while its shorter side stays at least [targetPx]. Never
 * below 1, so a small photo is decoded as it is.
 */
internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    val shorter = minOf(width, height)
    if (shorter <= 0 || targetPx <= 0) return sample
    while (shorter / (sample * 2) >= targetPx) sample *= 2
    return sample
}

private fun decodeForAvatar(bytes: ByteArray, sizePx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

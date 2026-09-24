// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException

/**
 * Loads a contact's photo, decoded for an avatar [sizePx] pixels across.
 * Returns null when there is no photo or it cannot be read; never throws.
 *
 * An interface here so any screen's [Avatar] can show photos without each
 * feature wiring a data source through: `:app` provides the implementation
 * through [LocalContactPhotoLoader].
 */
fun interface ContactPhotoLoader {
    suspend fun load(photoUri: String, sizePx: Int): ImageBitmap?
}

/** The app's photo loader; null (previews, most tests) means avatars show letters only. */
val LocalContactPhotoLoader = staticCompositionLocalOf<ContactPhotoLoader?> { null }

/** The photo for [photoUri], or null until it has loaded, or when there is none. */
@Composable
internal fun rememberContactPhoto(photoUri: String?, sizePx: Int): ImageBitmap? {
    val loader = LocalContactPhotoLoader.current
    val photo by produceState<ImageBitmap?>(initialValue = null, photoUri, sizePx, loader) {
        value = if (photoUri == null || loader == null) null else try {
            loader.load(photoUri, sizePx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    return photo
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Written from the task spec, independently of ContactPhotoCache's own
 * implementation: covers the fetch/decode/cache contract of
 * `ContactPhotoLoader.load` and the `sampleSizeFor` downsampling helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactPhotoCacheTest {

    // `ImageBitmap(width, height)` throws under this project's Robolectric setup
    // (NPE from android.graphics.Bitmap.createBitmap -- see report); going through
    // a real android.graphics.Bitmap first works and is a fine stand-in "decoded
    // image" for these tests, which only care whether a non-null result is produced.
    private fun fakeImageBitmap(): ImageBitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test fun `load returns null and does not decode when there are no photo bytes`() = runTest {
        val telephony = FakeTelephonyDataSource()
        var decodeCalls = 0
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> decodeCalls++; fakeImageBitmap() })

        val result = cache.load("content://com.android.contacts/photo/1", 96)

        assertNull(result)
        assertEquals(0, decodeCalls)
        assertEquals(listOf("content://com.android.contacts/photo/1"), telephony.loadedContactPhotos)
    }

    @Test fun `load returns the decoded image when bytes are present`() = runTest {
        val telephony = FakeTelephonyDataSource()
        telephony.contactPhotos["content://x/1"] = byteArrayOf(1, 2, 3)
        var decodeCalls = 0
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> decodeCalls++; fakeImageBitmap() })

        val result = cache.load("content://x/1", 96)

        assertNotNull(result)
        assertEquals(1, decodeCalls)
    }

    @Test fun `a successful load is cached -- telephony is asked once for repeats`() = runTest {
        val telephony = FakeTelephonyDataSource()
        telephony.contactPhotos["content://x/1"] = byteArrayOf(1, 2, 3)
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> fakeImageBitmap() })

        cache.load("content://x/1", 96)
        cache.load("content://x/1", 96)
        cache.load("content://x/1", 96)

        assertEquals(1, telephony.loadedContactPhotos.size)
    }

    @Test fun `a missing photo is also cached as a negative result`() = runTest {
        val telephony = FakeTelephonyDataSource()
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> fakeImageBitmap() })

        cache.load("content://x/none", 96)
        cache.load("content://x/none", 96)

        assertEquals(1, telephony.loadedContactPhotos.size)
    }

    @Test fun `a decode failure (null) is also cached as a negative result`() = runTest {
        val telephony = FakeTelephonyDataSource()
        telephony.contactPhotos["content://x/2"] = byteArrayOf(9)
        var decodeCalls = 0
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> decodeCalls++; null })

        val first = cache.load("content://x/2", 96)
        val second = cache.load("content://x/2", 96)

        assertNull(first)
        assertNull(second)
        assertEquals(1, telephony.loadedContactPhotos.size)
        assertEquals(1, decodeCalls)
    }

    @Test fun `a decode that throws is treated as a missing photo, not propagated`() = runTest {
        val telephony = FakeTelephonyDataSource()
        telephony.contactPhotos["content://x/3"] = byteArrayOf(9)
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> throw RuntimeException("boom") })

        val result = cache.load("content://x/3", 96)

        assertNull(result)
    }

    @Test fun `when telephony's loadContactPhoto throws, load returns null and does not decode`() = runTest {
        val telephony = FakeTelephonyDataSource()
        telephony.contactPhotoError = RuntimeException("provider failure")
        var decodeCalls = 0
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> decodeCalls++; fakeImageBitmap() })

        val result = cache.load("content://x/5", 96)

        assertNull(result)
        assertEquals(0, decodeCalls)
    }

    @Test fun `different sizePx for the same uri are separate cache entries, each fetches`() = runTest {
        val telephony = FakeTelephonyDataSource()
        telephony.contactPhotos["content://x/4"] = byteArrayOf(9)
        val cache = ContactPhotoCache(telephony, decode = { _, _ -> fakeImageBitmap() })

        cache.load("content://x/4", 48)
        cache.load("content://x/4", 96)
        cache.load("content://x/4", 48) // repeat of the first size: still only 2 total fetches

        assertEquals(2, telephony.loadedContactPhotos.size)
    }

    // -- sampleSizeFor -------------------------------------------------------

    @Test fun `sampleSizeFor shrinks 720x720 as far as possible while staying above target 144`() {
        assertEquals(4, sampleSizeFor(720, 720, 144))
    }

    @Test fun `sampleSizeFor shrinks 720x720 as far as possible while staying above target 96`() {
        // 720/8=90 < 96, so 8 is too aggressive; 720/4=180 >= 96 is the max valid step.
        assertEquals(4, sampleSizeFor(720, 720, 96))
    }

    @Test fun `sampleSizeFor is 1 when the image is already smaller than the target`() {
        assertEquals(1, sampleSizeFor(100, 100, 144))
    }

    @Test fun `sampleSizeFor uses the shorter side for a non-square image`() {
        // shorter side 500: 500/4=125 >= 120, 500/8=62 < 120.
        assertEquals(4, sampleSizeFor(1000, 500, 120))
    }

    @Test fun `sampleSizeFor never goes below 1 for non-positive width`() {
        assertEquals(1, sampleSizeFor(0, 100, 96))
        assertEquals(1, sampleSizeFor(-10, 100, 96))
    }

    @Test fun `sampleSizeFor never goes below 1 for non-positive height`() {
        assertEquals(1, sampleSizeFor(100, 0, 96))
        assertEquals(1, sampleSizeFor(100, -10, 96))
    }

    @Test fun `sampleSizeFor never goes below 1 for a non-positive target`() {
        assertEquals(1, sampleSizeFor(720, 720, 0))
        assertEquals(1, sampleSizeFor(720, 720, -5))
    }
}

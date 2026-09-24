// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Written from the task spec, independently of RealTelephonyDataSource's own
 * implementation: `loadContactPhoto` must return null, without reading
 * anything, for any URI that is not a contacts-provider URI. The positive
 * (real contacts-provider) case needs a real provider and is out of scope
 * here per the task brief. Construction mirrors other core:telephony
 * Robolectric tests (e.g. PhoneNumberNormalizerContextTest): a plain
 * application Context via ApplicationProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactPhotoLoadTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dataSource by lazy { RealTelephonyDataSource(context) }

    @Test fun `an http url is not a contacts-provider uri`() = runBlocking {
        assertNull(dataSource.loadContactPhoto("http://example.com/photo.jpg"))
    }

    @Test fun `a file uri is not a contacts-provider uri`() = runBlocking {
        assertNull(dataSource.loadContactPhoto("file:///sdcard/photo.jpg"))
    }

    @Test fun `a content uri with a different authority is not a contacts-provider uri`() = runBlocking {
        assertNull(dataSource.loadContactPhoto("content://sms/1"))
    }

    @Test fun `an unparseable string is not a contacts-provider uri`() = runBlocking {
        assertNull(dataSource.loadContactPhoto("::not a uri::"))
    }

    @Test fun `a blank string is not a contacts-provider uri`() = runBlocking {
        assertNull(dataSource.loadContactPhoto(""))
    }

    @Test fun `a blank-with-whitespace string is not a contacts-provider uri`() = runBlocking {
        assertNull(dataSource.loadContactPhoto("   "))
    }
}

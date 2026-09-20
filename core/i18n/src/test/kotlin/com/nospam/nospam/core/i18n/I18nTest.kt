// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.i18n

import org.junit.Assert.*
import org.junit.Test

class I18nTest {
    @Test fun `isRtl detects fa`() {
        assertTrue(LocaleHelper.isRtl("fa"))
        assertTrue(LocaleHelper.isRtl("fa-IR"))
        assertFalse(LocaleHelper.isRtl("en"))
        assertTrue(LocaleHelper.isRtl("ar"))
    }

    @Test fun `BidiHelper wraps preserves content`() {
        val phone = "+98 912 123 4567"
        val wrapped = BidiHelper.wrap(phone)
        assertTrue(wrapped.contains(phone))
    }

    @Test fun `DateFormatter relative formats`() {
        val now = System.currentTimeMillis()
        assertEquals("now", DateFormatter.formatRelative(now))
        assertTrue(DateFormatter.formatTime(now).contains(":"))
    }
}

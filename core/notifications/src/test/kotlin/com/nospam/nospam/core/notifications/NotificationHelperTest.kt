// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.notifications

import com.nospam.nospam.core.model.TelephonyConstants
import org.junit.Assert.*
import org.junit.Test

class NotificationHelperTest {
    @Test fun `channel ids are distinct`() {
        assertNotEquals(NotificationHelper.CHANNEL_ID_MESSAGES, NotificationHelper.CHANNEL_ID_SPAM)
    }
    @Test fun `telephony constants used for reply`() {
        assertEquals("android.intent.action.RESPOND_VIA_MESSAGE", TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE)
    }
    @Test fun `key text reply is defined`() {
        assertEquals("key_text_reply", NotificationHelper.KEY_TEXT_REPLY)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.app.role.RoleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/**
 * DefaultSmsApp: isHeld/requestIntent, a priority-5 target per the task brief
 * with no prior dedicated test file (core-telephony.md). Q+ path uses
 * RoleManager, which Robolectric shadows via ShadowRoleManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DefaultSmsAppTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val shadowRoleManager: ShadowRoleManager
        get() = shadowOf(context.getSystemService(RoleManager::class.java))

    @Test fun `isHeld is false when the role is not held`() {
        assertFalse(DefaultSmsApp.isHeld(context))
    }

    @Test fun `isHeld is true once the role is granted`() {
        shadowRoleManager.addHeldRole(RoleManager.ROLE_SMS)
        assertTrue(DefaultSmsApp.isHeld(context))
    }

    @Test fun `requestIntent is non-null when the role is not yet held`() {
        val intent = DefaultSmsApp.requestIntent(context)
        assertTrue(intent != null)
    }

    @Test fun `requestIntent is null once the role is already held`() {
        shadowRoleManager.addHeldRole(RoleManager.ROLE_SMS)
        assertNull(DefaultSmsApp.requestIntent(context))
    }
}

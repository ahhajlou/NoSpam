package com.nospam.nospam.feature.onboarding

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen itself holds its state in `remember` and has no state holder, so
 * there is nothing else here worth unit-testing; the rest of onboarding is
 * covered end-to-end. This one function is the exception, because the list it
 * returns is used for both the request and the check, and a drift between the
 * two would strand a user on the onboarding screen with every permission
 * apparently granted.
 */
class RequiredPermissionsTest {

    @Test fun `the four SMS and contacts permissions are always requested`() {
        val expected = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
        )
        assertTrue(requiredPermissions(Build.VERSION_CODES.O).containsAll(expected))
        assertTrue(requiredPermissions(Build.VERSION_CODES.TIRAMISU).containsAll(expected))
    }

    @Test fun `notifications are not requested below API 33`() {
        assertFalse(
            requiredPermissions(Build.VERSION_CODES.S_V2)
                .contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test fun `notifications are requested from API 33`() {
        assertTrue(
            requiredPermissions(Build.VERSION_CODES.TIRAMISU)
                .contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test fun `the list has no duplicates`() {
        val perms = requiredPermissions(Build.VERSION_CODES.TIRAMISU)
        assertEquals(perms.size, perms.toSet().size)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Only the default SMS app may write the SMS provider. A test calls [require]
 * to make this test package the default for the rest of the test (through the
 * shell, which may assign roles); the rule hands the role back to its previous
 * holder afterwards. The test APK qualifies for the role only because its
 * manifest declares the role's required components (two inert stubs, see the
 * androidTest manifest). Skips the test when the role cannot be taken.
 *
 * While a test holds the role, a real incoming SMS goes to the inert stub and
 * is lost: run these on an emulator or a spare device.
 */
class SmsRoleRule : TestRule {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var taken = false
    private var previousHolder: String? = null

    fun require() {
        if (taken) return
        taken = true
        previousHolder = shell("cmd role get-role-holders android.app.role.SMS").trim().ifEmpty { null }
        var output = ""
        if (previousHolder != context.packageName) {
            output = shell("cmd role add-role-holder android.app.role.SMS ${context.packageName}")
            // Role changes land asynchronously.
            for (attempt in 0 until 50) {
                if (holdsSmsRole()) break
                Thread.sleep(100)
            }
        }
        Assume.assumeTrue(
            "Could not make ${context.packageName} the default SMS app (was $previousHolder; shell said: $output)",
            holdsSmsRole(),
        )
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                val previous = previousHolder
                if (taken && previous != null && previous != context.packageName) {
                    shell("cmd role add-role-holder android.app.role.SMS $previous")
                }
                taken = false
                previousHolder = null
            }
        }
    }

    // getDefaultSmsPackage stays stale in this process after the shell changes the role.
    private fun holdsSmsRole(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(android.app.role.RoleManager::class.java)
                .isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}

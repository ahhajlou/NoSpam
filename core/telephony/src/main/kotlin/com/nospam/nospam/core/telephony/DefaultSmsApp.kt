package com.nospam.nospam.core.telephony

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/**
 * Whether this app currently holds the default-SMS role, and how to ask for it.
 *
 * This check was reimplemented at six call sites and the request at two, across
 * `feature:onboarding`, `feature:settings`, `feature:conversations`, `:app` and
 * `core:data`. They had already drifted: most guarded the nullable
 * `getSystemService` result, one did not and would throw on a device without
 * RoleManager. Consolidating also restores CLAUDE.md §5, which makes
 * `core:telephony` the only module allowed to touch these platform APIs.
 *
 * API note (CLAUDE.md §7): there is no public intent action for requesting a
 * role, so `createRequestRoleIntent` is the only supported entry point on Q+.
 * Below Q the equivalent is `ACTION_CHANGE_DEFAULT`, which is nested under
 * `Telephony.Sms.Intents`, not `Telephony.Sms`.
 */
object DefaultSmsApp {

    /** True when this package is the active default SMS app. Never throws. */
    fun isHeld(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            @Suppress("DEPRECATION")
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Intent that asks the user to make this the default SMS app, or null when
     * the role is already held or cannot be requested. Callers launch it
     * themselves through the Activity Result API, so this stays testable and
     * free of any launcher dependency.
     */
    fun requestIntent(context: Context): Intent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            when {
                roleManager == null -> null
                roleManager.isRoleHeld(RoleManager.ROLE_SMS) -> null
                else -> roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            }
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    } catch (_: Exception) {
        null
    }
}

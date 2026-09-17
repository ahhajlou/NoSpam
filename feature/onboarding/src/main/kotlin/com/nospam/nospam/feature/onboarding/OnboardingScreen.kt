package com.nospam.nospam.feature.onboarding

import com.nospam.nospam.core.telephony.DefaultSmsApp
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun OnboardingScreen(onComplete: () -> Unit = {}) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(hasRequiredPermissions(context)) }
    var isDefaultSms by remember { mutableStateOf(isDefaultSmsApp(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Checked against the required list, not the result: denying an optional
        // permission must not strand the user on this screen.
        hasPermissions = hasRequiredPermissions(context)
        if (hasPermissions && isDefaultSms) onComplete()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultSms = isDefaultSmsApp(context)
        if (hasPermissions && isDefaultSms) onComplete()
    }

    Column(
        // Full screen with no bar: nothing else applies system-bar insets here.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        if (!hasPermissions) {
            Button(onClick = {
                permissionLauncher.launch(requestedPermissions().toTypedArray())
            }) { Text(stringResource(R.string.grant)) }
            Spacer(Modifier.height(12.dp))
        } else {
            Text(stringResource(R.string.granted), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
        }
        if (!isDefaultSms) {
            Button(onClick = { requestDefaultSmsRole(context, roleLauncher) }) {
                Text(stringResource(R.string.set_default))
            }
        } else {
            Text(stringResource(R.string.is_default), color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        if (hasPermissions && isDefaultSms) {
            Button(onClick = onComplete) { Text(stringResource(R.string.continue_btn)) }
        }
    }
}

/**
 * Permissions the app cannot work without, and which therefore gate onboarding.
 *
 * This list was previously written twice, once here and once inline in the
 * grant button, which meant the API 33 notification entry could drift between
 * what we request and what we check.
 *
 * [sdkInt] is a parameter rather than a read of [Build.VERSION.SDK_INT] so the
 * version branch is testable off-device; callers pass the real value.
 */
internal fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
    add(android.Manifest.permission.READ_SMS)
    add(android.Manifest.permission.SEND_SMS)
    add(android.Manifest.permission.RECEIVE_SMS)
    add(android.Manifest.permission.READ_CONTACTS)
    // Phone: enumerating SIMs (SubscriptionManager.getActiveSubscriptionInfoList)
    // needs READ_PHONE_STATE, and a SIM's own number needs READ_PHONE_NUMBERS from
    // API 33. Verified 2026-09-17 that Google Messages gates on these too: denying
    // Phone alone leaves it stuck on its "You're almost done" screen.
    add(android.Manifest.permission.READ_PHONE_STATE)
    // READ_PHONE_NUMBERS only exists from API 30. Gating on it below that would be
    // unsatisfiable — checkSelfPermission returns DENIED for a permission the
    // manifest cannot hold, stranding the user on onboarding forever.
    if (sdkInt >= Build.VERSION_CODES.R) add(android.Manifest.permission.READ_PHONE_NUMBERS)
}

/**
 * Requested with the rest, but not required: an SMS app is perfectly usable
 * with notifications off, the user simply is not alerted. Google Messages runs
 * with them denied too. Gating on this would also trap anyone who turns
 * notifications off later, since [requiredPermissions] decides whether the app
 * returns to onboarding.
 *
 * POST_NOTIFICATIONS only exists from Tiramisu; requesting it below 33 is a
 * no-op that still shows up as "denied" on some OEM builds.
 */
internal fun optionalPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(android.Manifest.permission.POST_NOTIFICATIONS)
}

/** Everything the grant button asks for: required first, then optional. */
internal fun requestedPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    requiredPermissions(sdkInt) + optionalPermissions(sdkInt)

/** True when every permission the app cannot work without is granted. */
fun hasRequiredPermissions(context: Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

private fun isDefaultSmsApp(context: Context): Boolean = DefaultSmsApp.isHeld(context)

private fun requestDefaultSmsRole(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    DefaultSmsApp.requestIntent(context)?.let(launcher::launch)
}

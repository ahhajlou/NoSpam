package com.nospam.nospam.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.core.telephony.DefaultSmsApp

/**
 * First run, and the screen the app returns to whenever a required permission
 * is missing (see `NoSpamNavHost.needsOnboarding`). It is a gate, so it says
 * what each step is for rather than showing bare buttons: someone who lands
 * back here after revoking contacts needs to know why the app is asking again.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit = {}) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(hasRequiredPermissions(context)) }
    var isDefaultSms by remember { mutableStateOf(isDefaultSmsApp(context)) }
    // Android stops showing the dialog after the second denial, so the grant
    // button would do nothing at all. Once that happens the only way forward is
    // the system settings page, and the screen has to say so.
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Checked against the required list, not the result: denying an optional
        // permission must not strand the user on this screen.
        hasPermissions = hasRequiredPermissions(context)
        permanentlyDenied = !hasPermissions && !context.canAskAgain()
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
        // It scrolls because two steps plus their explanations do not fit a
        // short screen at large font sizes.
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.welcome),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        SetupStep(
            icon = Icons.Outlined.Shield,
            title = stringResource(R.string.step_permissions_title),
            body = stringResource(
                if (permanentlyDenied) R.string.step_permissions_blocked else R.string.step_permissions_body
            ),
            done = hasPermissions,
            doneLabel = stringResource(R.string.granted),
            actionLabel = stringResource(
                if (permanentlyDenied) R.string.action_open_settings else R.string.grant
            ),
            onAction = {
                if (permanentlyDenied) {
                    openAppSettings(context)
                } else {
                    permissionLauncher.launch(requestedPermissions().toTypedArray())
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        SetupStep(
            icon = Icons.Outlined.Drafts,
            title = stringResource(R.string.step_default_title),
            body = stringResource(R.string.step_default_body),
            done = isDefaultSms,
            doneLabel = stringResource(R.string.is_default),
            actionLabel = stringResource(R.string.set_default),
            onAction = { requestDefaultSmsRole(context, roleLauncher) },
        )

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onComplete,
            enabled = hasPermissions && isDefaultSms,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.continue_btn)) }
    }
}

/**
 * One setup step: what it is, why the app needs it, and either its action or a
 * check mark once it is satisfied.
 */
@Composable
private fun SetupStep(
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean,
    doneLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (done) {
                    Text(
                        doneLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
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

/**
 * False once the system has stopped offering the dialog for every missing
 * permission, which is what "don't ask again" amounts to from API 30 on: the
 * second denial is permanent until the user changes it in system settings.
 */
private fun Context.canAskAgain(): Boolean {
    val activity = findActivity() ?: return true
    return requiredPermissions().any {
        ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun isDefaultSmsApp(context: Context): Boolean = DefaultSmsApp.isHeld(context)

private fun requestDefaultSmsRole(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    // Launch can still throw if the activity is not in a valid state; the status
    // refreshes on resume, so swallowing keeps the screen truthful either way.
    runCatching { DefaultSmsApp.requestIntent(context)?.let(launcher::launch) }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Onboarding")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Onboarding dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Onboarding RTL", locale = "fa")
@Composable
fun OnboardingScreenPreview() {
    NoSpamTheme { OnboardingScreen() }
}

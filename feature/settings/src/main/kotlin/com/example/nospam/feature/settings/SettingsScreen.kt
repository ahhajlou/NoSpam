package com.example.nospam.feature.settings

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.nospam.core.i18n.LocaleHelper

private data class LanguageOption(val tag: String, val title: String, val subtitle: String)

private val LANGUAGE_OPTIONS = listOf(
    LanguageOption(LocaleHelper.SELECTED_SYSTEM, "System default", "Follow device language"),
    LanguageOption("en", "English", "English"),
    LanguageOption("fa", "فارسی", "Persian · RTL"),
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    var isDefault by remember { mutableStateOf(isDefaultSmsApp(context)) }
    var notificationsEnabled by remember { mutableStateOf(areNotificationsEnabled(context)) }
    var bubblesAllowed by remember { mutableStateOf(areBubblesAllowed(context)) }
    var selectedLanguage by remember { mutableStateOf(LocaleHelper.selectedOption()) }

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDataDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }

    var spamProtectionEnabled by rememberSaveable { mutableStateOf(true) }
    var autoDownloadMms by rememberSaveable { mutableStateOf(true) }
    var groupMode by rememberSaveable { mutableStateOf("mass") }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = isDefaultSmsApp(context)
    }

    // Refresh system-backed state every time the user returns from system Settings
    // or the role request dialog. This is what makes Default-SMS / Notifications
    // subtitles correct instead of stale.
    LifecycleResumeEffect(Unit) {
        isDefault = isDefaultSmsApp(context)
        notificationsEnabled = areNotificationsEnabled(context)
        bubblesAllowed = areBubblesAllowed(context)
        selectedLanguage = LocaleHelper.selectedOption()
        onPauseOrDispose { }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SectionHeader("General") }
        item {
            SettingsRow(
                title = "Notifications",
                subtitle = if (notificationsEnabled) "On" else "Off — tap to enable",
                onClick = { openAppNotificationSettings(context) }
            )
        }
        item {
            SettingsRow(
                title = "Bubbles",
                subtitle = when (bubblesAllowed) {
                    true -> "Allowed"
                    false -> "Off — tap to change"
                    null -> "System setting"
                },
                onClick = { openBubbleSettings(context) }
            )
        }
        item {
            SettingsRow(
                title = "Language",
                subtitle = languageDisplayName(selectedLanguage),
                onClick = { showLanguageDialog = true }
            )
        }

        item { SectionHeader("Privacy & protection") }
        item {
            SettingsRow(
                title = "Default SMS app",
                subtitle = if (isDefault) "NoSpam is default" else "Tap to set as default",
                trailing = {
                    if (isDefault) Text("✓", color = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick = { if (!isDefault) requestDefaultSmsRole(context, roleLauncher) }
            )
        }
        item {
            SwitchRow(
                title = "Spam protection",
                subtitle = "Filter spam, scams and phishing on this device",
                checked = spamProtectionEnabled,
                onCheckedChange = { spamProtectionEnabled = it }
            )
        }
        item {
            SettingsRow(
                title = "Your data in Messages",
                subtitle = "Kept on this device",
                onClick = { showDataDialog = true }
            )
        }

        item { SectionHeader("Advanced") }
        item {
            SwitchRow(
                title = "Auto-download MMS",
                subtitle = "Automatically download multimedia messages",
                checked = autoDownloadMms,
                onCheckedChange = { autoDownloadMms = it }
            )
        }
        item {
            SettingsRow(
                title = "Group messaging",
                subtitle = if (groupMode == "mass") "Mass text (individual replies)" else "Group MMS",
                onClick = { showGroupDialog = true }
            )
        }

        item { SectionHeader("About") }
        item {
            // Informational only: no chevron, no click action.
            InfoRow(title = "Version info", subtitle = appVersion(context))
        }
        item {
            SettingsRow(
                title = "Terms of service",
                subtitle = null,
                onClick = { showTermsDialog = true }
            )
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("App language") },
            text = {
                Column {
                    LANGUAGE_OPTIONS.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selectedLanguage == option.tag,
                                    role = Role.RadioButton,
                                    onClick = {
                                        applyLanguage(option.tag)
                                        selectedLanguage = option.tag
                                        showLanguageDialog = false
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == option.tag,
                                onClick = null
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                Text(option.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("Close") }
            }
        )
    }

    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("Group messaging") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = groupMode == "mass", role = Role.RadioButton, onClick = { groupMode = "mass"; showGroupDialog = false })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = groupMode == "mass", onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("Mass text", style = MaterialTheme.typography.bodyLarge)
                            Text("Send an SMS reply to all and get individual replies", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = groupMode == "mms", role = Role.RadioButton, onClick = { groupMode = "mms"; showGroupDialog = false })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = groupMode == "mms", onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("Group MMS", style = MaterialTheme.typography.bodyLarge)
                            Text("Everyone sees replies in one group", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false }) { Text("Close") }
            }
        )
    }

    if (showDataDialog) {
        AlertDialog(
            onDismissRequest = { showDataDialog = false },
            title = { Text("Your data in Messages") },
            text = { Text("Messages stay in the system SMS store. NoSpam only keeps blocklist entries and spam corrections on this device. Nothing is uploaded.") },
            confirmButton = {
                TextButton(onClick = { showDataDialog = false }) { Text("Got it") }
            }
        )
    }

    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = { Text("Terms of service") },
            text = { Text("Terms are not published yet for this build.") },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) { Text("Close") }
            }
        )
    }
}

private fun languageDisplayName(tag: String): String = when {
    tag == LocaleHelper.SELECTED_SYSTEM -> "System default"
    tag.startsWith("fa") -> "فارسی"
    else -> "English"
}

private fun applyLanguage(tag: String) {
    if (tag == LocaleHelper.SELECTED_SYSTEM) {
        LocaleHelper.clearToSystemDefault()
    } else {
        LocaleHelper.setLocale(tag)
    }
}

private fun isDefaultSmsApp(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        rm.isRoleHeld(RoleManager.ROLE_SMS)
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
}

private fun requestDefaultSmsRole(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return
            if (!rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
            launcher.launch(intent)
        }
    } catch (_: Exception) {
        // Role request can throw if the activity is not in a valid state; status
        // refreshes on resume so the row stays truthful.
    }
}

private fun areNotificationsEnabled(context: Context): Boolean {
    return try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: Exception) {
        true
    }
}

private fun areBubblesAllowed(context: Context): Boolean? {
    return try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                // areBubblesAllowed() is deprecated since API 31; getBubblePreference()
                // distinguishes ALL / SELECTED / NONE.
                manager.getBubblePreference() != NotificationManager.BUBBLE_PREFERENCE_NONE
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                @Suppress("DEPRECATION")
                manager.areBubblesAllowed()
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        // No-op: staying in Settings is better than crashing.
    }
}

private fun openBubbleSettings(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            openAppNotificationSettings(context)
        }
    } catch (_: Exception) {
        openAppNotificationSettings(context)
    }
}

private fun appVersion(context: Context): String {
    return try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
        "${info.versionName ?: "1.0"} (build $code)"
    } catch (_: Exception) {
        "1.0.0"
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
    HorizontalDivider()
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider()
}

@Composable
private fun InfoRow(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

// Previews
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, locale = "fa")
@Composable
fun SettingsScreenPreview() {
    com.example.nospam.core.designsystem.theme.NoSpamTheme {
        SettingsScreen()
    }
}

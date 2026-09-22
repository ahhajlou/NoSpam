// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.component.SettingsGroup
import com.nospam.nospam.core.designsystem.component.isolateIfPhoneNumber
import com.nospam.nospam.core.designsystem.component.SettingsItem
import com.nospam.nospam.core.designsystem.component.SettingsSectionHeader
import com.nospam.nospam.core.designsystem.component.SettingsSwitchItem
import com.nospam.nospam.core.designsystem.component.TopBarNavigation
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.core.i18n.LocaleHelper
import com.nospam.nospam.core.telephony.DefaultSmsApp
import kotlinx.coroutines.launch

// Settings whose storage does not exist yet are shown disabled rather than as
// controls that look live and silently forget (docs/UI-POLISH-PLAN.md §3).
private const val NOT_WIRED_YET = false

@Composable
fun GeneralSettingsScreen(
    onNavigateUp: () -> Unit = {},
) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultSmsApp(context)) }
    var notificationsEnabled by remember { mutableStateOf(areNotificationsEnabled(context)) }
    var bubblesAllowed by remember { mutableStateOf(areBubblesAllowed(context)) }
    var selectedLanguage by remember { mutableStateOf(LocaleHelper.selectedOption()) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { isDefault = isDefaultSmsApp(context) }

    // Refresh system-backed state every time the user returns from system Settings
    // or the role request dialog. This is what makes the Default-SMS and
    // Notifications subtitles correct instead of stale.
    LifecycleResumeEffect(Unit) {
        isDefault = isDefaultSmsApp(context)
        notificationsEnabled = areNotificationsEnabled(context)
        bubblesAllowed = areBubblesAllowed(context)
        selectedLanguage = LocaleHelper.selectedOption()
        onPauseOrDispose { }
    }

    SettingsScaffold(
        title = stringResource(R.string.section_general),
        navigation = TopBarNavigation.Back(onNavigateUp),
    ) {
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.default_sms_title),
                supportingText = if (isDefault) stringResource(R.string.default_sms_on) else stringResource(R.string.default_sms_off),
                trailingContent = if (isDefault) {
                    { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                } else null,
                onClick = { if (!isDefault) requestDefaultSmsRole(context, roleLauncher) },
            )
            SettingsItem(
                title = stringResource(R.string.notif_title),
                supportingText = if (notificationsEnabled) stringResource(R.string.notif_on) else stringResource(R.string.notif_off),
                onClick = { openAppNotificationSettings(context) },
            )
            SettingsItem(
                title = stringResource(R.string.bubbles_title),
                supportingText = when (bubblesAllowed) {
                    true -> stringResource(R.string.bubbles_allowed)
                    false -> stringResource(R.string.bubbles_off)
                    null -> stringResource(R.string.bubbles_system)
                },
                onClick = { openBubbleSettings(context) },
            )
            SettingsItem(
                title = stringResource(R.string.lang_title),
                supportingText = languageDisplayName(selectedLanguage),
                onClick = { showLanguageDialog = true },
            )
        }

        SettingsSectionHeader(stringResource(R.string.section_appearance))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.theme_title),
                supportingText = stringResource(R.string.theme_system),
                enabled = NOT_WIRED_YET,
                onClick = {},
            )
            SettingsSwitchItem(
                title = stringResource(R.string.dynamic_color_title),
                supportingText = stringResource(R.string.dynamic_color_sub),
                checked = false,
                onCheckedChange = {},
                enabled = NOT_WIRED_YET,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.sounds_title),
                supportingText = stringResource(R.string.sounds_sub),
                checked = false,
                onCheckedChange = {},
                enabled = NOT_WIRED_YET,
            )
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            selected = selectedLanguage,
            onSelect = {
                applyLanguage(it)
                selectedLanguage = it
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
fun SimSettingsScreen(
    subscriptionId: Int,
    onNavigateUp: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val sim = viewModel.simById(subscriptionId)
    var showGroupDialog by rememberSaveable { mutableStateOf(false) }
    var groupMode by rememberSaveable { mutableStateOf("mass") }

    SettingsScaffold(
        title = sim?.displayName ?: stringResource(R.string.sim_title),
        navigation = TopBarNavigation.Back(onNavigateUp),
    ) {
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.group_title),
                supportingText = if (groupMode == "mass") stringResource(R.string.group_mass_individual) else stringResource(R.string.group_mms),
                onClick = { showGroupDialog = true },
            )
            SettingsSwitchItem(
                title = stringResource(R.string.mms_title),
                supportingText = stringResource(R.string.mms_sub),
                checked = true,
                onCheckedChange = {},
                enabled = NOT_WIRED_YET,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.mms_roaming_title),
                supportingText = stringResource(R.string.mms_roaming_sub),
                checked = false,
                onCheckedChange = {},
                enabled = NOT_WIRED_YET,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.delivery_reports_title),
                supportingText = stringResource(R.string.delivery_reports_sub),
                checked = false,
                onCheckedChange = {},
                enabled = NOT_WIRED_YET,
            )
        }

        SettingsSectionHeader(stringResource(R.string.sim_section_details))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.sim_number_title),
                supportingText = sim?.number?.let(::isolateIfPhoneNumber) ?: stringResource(R.string.sim_number_unknown),
                onClick = null,
            )
        }
    }

    if (showGroupDialog) {
        GroupMessagingDialog(
            selected = groupMode,
            onSelect = {
                groupMode = it
                showGroupDialog = false
            },
            onDismiss = { showGroupDialog = false },
        )
    }
}

@Composable
fun SpamSettingsScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: SpamSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.section_spam),
        navigation = TopBarNavigation.Back(onNavigateUp),
    ) {
        SettingsGroup {
            SettingsSwitchItem(
                title = stringResource(R.string.spam_title),
                supportingText = stringResource(R.string.spam_sub),
                checked = state.spamProtection,
                onCheckedChange = viewModel::setSpamProtection,
            )
            SettingsItem(
                title = stringResource(R.string.blocked_senders_title),
                supportingText = stringResource(R.string.blocked_senders_sub),
                enabled = NOT_WIRED_YET,
                onClick = {},
            )
            SettingsSwitchItem(
                title = stringResource(R.string.warn_contacts_title),
                supportingText = stringResource(R.string.warn_contacts_sub),
                checked = false,
                onCheckedChange = {},
                enabled = NOT_WIRED_YET,
            )
        }
    }
}

@Composable
fun AdvancedSettingsScreen(
    onNavigateUp: () -> Unit = {},
    onRecheck: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDataDialog by rememberSaveable { mutableStateOf(false) }
    val recheckStarted = stringResource(R.string.recheck_started)

    SettingsScaffold(
        title = stringResource(R.string.section_advanced),
        navigation = TopBarNavigation.Back(onNavigateUp),
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.scan_title),
                supportingText = stringResource(R.string.scan_sub),
                onClick = {
                    onRecheck()
                    scope.launch { snackbarHostState.showSnackbar(recheckStarted) }
                },
            )
            SettingsItem(
                title = stringResource(R.string.auto_delete_spam_title),
                supportingText = stringResource(R.string.auto_delete_spam_sub),
                enabled = NOT_WIRED_YET,
                onClick = {},
            )
            SettingsItem(
                title = stringResource(R.string.data_title),
                supportingText = stringResource(R.string.data_sub),
                onClick = { showDataDialog = true },
            )
        }
    }

    if (showDataDialog) {
        AlertDialog(
            onDismissRequest = { showDataDialog = false },
            title = { Text(stringResource(R.string.data_title)) },
            text = { Text(stringResource(R.string.data_text)) },
            confirmButton = {
                TextButton(onClick = { showDataDialog = false }) { Text(stringResource(R.string.data_ok)) }
            },
        )
    }
}

@Composable
fun AboutSettingsScreen(
    onNavigateUp: () -> Unit = {},
) {
    val context = LocalContext.current
    var showTermsDialog by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.section_about),
        navigation = TopBarNavigation.Back(onNavigateUp),
    ) {
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.about_version),
                supportingText = appVersion(context),
                onClick = null,
            )
            SettingsItem(
                title = stringResource(R.string.about_terms),
                onClick = { showTermsDialog = true },
            )
        }
    }

    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = { Text(stringResource(R.string.about_terms)) },
            text = { Text(stringResource(R.string.terms_text)) },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

private data class LanguageOption(val tag: String, val title: String, val subtitle: String)

@Composable
private fun languageOptions() = listOf(
    LanguageOption(
        LocaleHelper.SELECTED_SYSTEM,
        stringResource(R.string.lang_system),
        stringResource(R.string.lang_follow),
    ),
    LanguageOption("en", stringResource(R.string.lang_english), stringResource(R.string.lang_english)),
    LanguageOption("fa", stringResource(R.string.lang_persian), stringResource(R.string.lang_persian_sub)),
)

@Composable
private fun LanguageDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_language)) },
        text = {
            Column {
                languageOptions().forEach { option ->
                    RadioRow(
                        selected = selected == option.tag,
                        title = option.title,
                        subtitle = option.subtitle,
                        onClick = { onSelect(option.tag) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun GroupMessagingDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_title)) },
        text = {
            Column {
                RadioRow(
                    selected = selected == "mass",
                    title = stringResource(R.string.group_mass),
                    subtitle = stringResource(R.string.group_mass_sub),
                    onClick = { onSelect("mass") },
                )
                RadioRow(
                    selected = selected == "mms",
                    title = stringResource(R.string.group_mms),
                    subtitle = stringResource(R.string.group_mms_sub),
                    onClick = { onSelect("mms") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun languageDisplayName(tag: String): String = when {
    tag == LocaleHelper.SELECTED_SYSTEM -> stringResource(R.string.lang_system)
    tag.startsWith("fa") -> stringResource(R.string.lang_persian)
    else -> stringResource(R.string.lang_english)
}

private fun applyLanguage(tag: String) {
    if (tag == LocaleHelper.SELECTED_SYSTEM) {
        LocaleHelper.clearToSystemDefault()
    } else {
        LocaleHelper.setLocale(tag)
    }
}

private fun isDefaultSmsApp(context: Context): Boolean = DefaultSmsApp.isHeld(context)

private fun requestDefaultSmsRole(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    // Launch can still throw if the activity is not in a valid state; the status
    // row refreshes on resume, so swallowing keeps it truthful either way.
    runCatching { DefaultSmsApp.requestIntent(context)?.let(launcher::launch) }
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun GeneralSettingsPreview() {
    NoSpamTheme { GeneralSettingsScreen() }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun SpamSettingsPreview() {
    NoSpamTheme { SpamSettingsScreen() }
}

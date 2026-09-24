// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import com.nospam.nospam.core.designsystem.theme.isDynamicColorSupported
import com.nospam.nospam.core.i18n.LocaleHelper
import com.nospam.nospam.core.model.SwipeAction
import com.nospam.nospam.core.model.ThemeSetting
import com.nospam.nospam.core.telephony.DefaultSmsApp
import kotlinx.coroutines.launch

@Composable
fun GeneralSettingsScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: GeneralSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val appearance by viewModel.uiState.collectAsState()
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    // Which direction's swipe dialog is open: true for right, false for left.
    var swipeDialogRight by rememberSaveable { mutableStateOf<Boolean?>(null) }
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
                supportingText = themeName(appearance.theme),
                onClick = { showThemeDialog = true },
            )
            // Hidden rather than disabled below Android 12: the device cannot
            // do it, so there is nothing to wait for.
            if (isDynamicColorSupported) {
                SettingsSwitchItem(
                    title = stringResource(R.string.dynamic_color_title),
                    supportingText = stringResource(R.string.dynamic_color_sub),
                    checked = appearance.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
            SettingsSwitchItem(
                title = stringResource(R.string.sounds_title),
                supportingText = stringResource(R.string.sounds_sub),
                checked = appearance.messageSounds,
                onCheckedChange = viewModel::setMessageSounds,
            )
        }

        SettingsSectionHeader(stringResource(R.string.section_swipe))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.swipe_right_title),
                supportingText = swipeActionName(appearance.swipeActions.right),
                onClick = { swipeDialogRight = true },
            )
            SettingsItem(
                title = stringResource(R.string.swipe_left_title),
                supportingText = swipeActionName(appearance.swipeActions.left),
                onClick = { swipeDialogRight = false },
            )
        }
    }

    swipeDialogRight?.let { right ->
        SwipeActionDialog(
            title = stringResource(if (right) R.string.swipe_right_title else R.string.swipe_left_title),
            selected = if (right) appearance.swipeActions.right else appearance.swipeActions.left,
            onSelect = { action ->
                val current = appearance.swipeActions
                viewModel.setSwipeActions(if (right) current.copy(right = action) else current.copy(left = action))
                swipeDialogRight = null
            },
            onDismiss = { swipeDialogRight = null },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            selected = appearance.theme,
            onSelect = {
                viewModel.setTheme(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
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
    // Collected, not read once: the SIM list loads asynchronously, and a page
    // opened before it arrives must fill in when it does.
    val state by viewModel.uiState.collectAsState()
    val sim = state.sims.firstOrNull { it.subscriptionId == subscriptionId }
    val needsMms = stringResource(R.string.needs_mms)
    val entered = state.enteredNumbers[subscriptionId]
    var editNumber by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = sim?.displayName ?: stringResource(R.string.sim_title),
        navigation = TopBarNavigation.Back(onNavigateUp),
    ) {
        SettingsGroup {
            // Group messaging and the two download settings only mean something
            // once MMS exists (TODO.md "MMS"). Shown, so the page says what is
            // coming, but disabled and saying why.
            SettingsItem(
                title = stringResource(R.string.group_title),
                supportingText = needsMms,
                enabled = false,
                onClick = {},
            )
            SettingsSwitchItem(
                title = stringResource(R.string.mms_title),
                supportingText = needsMms,
                checked = false,
                onCheckedChange = {},
                enabled = false,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.mms_roaming_title),
                supportingText = needsMms,
                checked = false,
                onCheckedChange = {},
                enabled = false,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.delivery_reports_title),
                supportingText = stringResource(R.string.delivery_reports_sub),
                checked = subscriptionId in state.deliveryReportSims,
                onCheckedChange = { viewModel.setDeliveryReports(subscriptionId, it) },
            )
        }

        SettingsSectionHeader(stringResource(R.string.sim_section_details))
        SettingsGroup {
            // Many carriers leave the number off the SIM, so the user can say it.
            val number = entered ?: sim?.number
            SettingsItem(
                title = stringResource(R.string.sim_number_title),
                supportingText = when {
                    number == null -> stringResource(R.string.sim_number_unknown_enter)
                    entered != null -> stringResource(R.string.sim_number_entered, isolateIfPhoneNumber(number))
                    else -> isolateIfPhoneNumber(number)
                },
                onClick = { editNumber = true },
            )
        }
    }

    if (editNumber) {
        SimNumberDialog(
            initial = entered ?: sim?.number.orEmpty(),
            canClear = entered != null,
            onSave = {
                viewModel.setSimNumber(subscriptionId, it)
                editNumber = false
            },
            onClear = {
                viewModel.setSimNumber(subscriptionId, null)
                editNumber = false
            },
            onDismiss = { editNumber = false },
        )
    }
}

/**
 * Whether [text] can be saved as a SIM's number: not blank, and only digits and
 * the usual phone punctuation (`+ - ( )` and spaces), because it is shown as a
 * number. Length is capped separately, by [MAX_SIM_NUMBER_LENGTH].
 */
internal fun isValidSimNumber(text: String): Boolean =
    text.isNotBlank() && text.all { it.isDigit() || it in "+-() " }

/** Longest number accepted; E.164 is at most 15 digits, this leaves room for formatting. */
private const val MAX_SIM_NUMBER_LENGTH = 24

@Composable
private fun SimNumberDialog(
    initial: String,
    canClear: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The cursor starts after the prefilled number, so typing continues it
    // rather than landing in the middle of it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val text = field.text
    val valid = isValidSimNumber(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sim_number_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sim_number_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { if (it.text.length <= MAX_SIM_NUMBER_LENGTH) field = it },
                    label = { Text(stringResource(R.string.sim_number_title)) },
                    singleLine = true,
                    isError = text.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    // A number reads left to right in every language.
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = valid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (canClear) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
fun SpamSettingsScreen(
    onNavigateUp: () -> Unit = {},
    onOpenSenders: () -> Unit = {},
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
                onClick = onOpenSenders,
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
private fun themeName(theme: ThemeSetting): String = when (theme) {
    ThemeSetting.SYSTEM -> stringResource(R.string.theme_system)
    ThemeSetting.LIGHT -> stringResource(R.string.theme_light)
    ThemeSetting.DARK -> stringResource(R.string.theme_dark)
}

@Composable
private fun swipeActionName(action: SwipeAction): String = stringResource(
    when (action) {
        SwipeAction.NONE -> R.string.swipe_none
        SwipeAction.ARCHIVE -> R.string.swipe_archive
        SwipeAction.DELETE -> R.string.swipe_delete
        SwipeAction.TOGGLE_READ -> R.string.swipe_toggle_read
    }
)

@Composable
private fun SwipeActionDialog(
    title: String,
    selected: SwipeAction,
    onSelect: (SwipeAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SwipeAction.entries.forEach { action ->
                    RadioRow(
                        selected = selected == action,
                        title = swipeActionName(action),
                        subtitle = null,
                        onClick = { onSelect(action) },
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
private fun ThemeDialog(selected: ThemeSetting, onSelect: (ThemeSetting) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_title)) },
        text = {
            Column {
                ThemeSetting.entries.forEach { theme ->
                    RadioRow(
                        selected = selected == theme,
                        title = themeName(theme),
                        subtitle = if (theme == ThemeSetting.SYSTEM) stringResource(R.string.theme_system_sub) else null,
                        onClick = { onSelect(theme) },
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
private fun RadioRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
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
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

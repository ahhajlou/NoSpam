package com.example.nospam.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isDefault by androidx.compose.runtime.remember { mutableStateOf(isDefaultSmsApp(context)) }
    val roleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        isDefault = isDefaultSmsApp(context)
    }
    var selectedLocale by androidx.compose.runtime.remember { mutableStateOf(com.example.nospam.core.i18n.LocaleHelper.currentLocaleTag()) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SectionHeader("General") }
        item { SettingsRow("Notifications", "On") }
        item { SettingsRow("Bubbles", "All") }
        item {
            Row(modifier = Modifier.fillMaxWidth().clickable {
                val newTag = if (selectedLocale.startsWith("fa")) "en" else "fa"
                com.example.nospam.core.i18n.LocaleHelper.setLocale(newTag)
                selectedLocale = newTag
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Language", style = MaterialTheme.typography.bodyLarge)
                    Text(if (selectedLocale.startsWith("fa")) "فارسی" else "English", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item { SectionHeader("Privacy & protection") }
        item {
            Row(modifier = Modifier.fillMaxWidth().clickable {
                if (!isDefault) {
                    requestDefaultSmsRole(context, roleLauncher)
                }
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default SMS app", style = MaterialTheme.typography.bodyLarge)
                    Text(if (isDefault) "NoSpam is default" else "Tap to set as default", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isDefault) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("✓", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
        }
        item { SettingsRow("Spam protection", "Help protect against spam") }
        item { SettingsRow("Your data in Messages", null) }
        item { SectionHeader("Advanced") }
        item {
            var checked by remember { mutableStateOf(true) }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-download MMS", style = MaterialTheme.typography.bodyLarge)
                    Text("Automatically download multimedia", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = { checked = it })
            }
        }
        item { SettingsRow("Group messaging", "Mass text") }
        item { SectionHeader("About") }
        item { SettingsRow("Version info", "1.0.0 (build 1)") }
        item { SettingsRow("Terms of service", null) }
    }
}

private fun isDefaultSmsApp(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
        rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
    } else {
        android.provider.Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
}

private fun requestDefaultSmsRole(context: android.content.Context, launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
        if (!rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
            launcher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS))
        }
    } else {
        val intent = android.content.Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
        launcher.launch(intent)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().clickable {}.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

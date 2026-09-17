package com.nospam.nospam.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.component.SettingsGroup
import com.nospam.nospam.core.designsystem.component.SettingsItem
import com.nospam.nospam.core.designsystem.component.TopBarNavigation
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme

/**
 * Settings landing page: one entry per section, plus one per active SIM.
 * Each entry opens its own page, which is how a messaging app's settings are
 * expected to be laid out and keeps any single page short enough to scan.
 */
@Composable
fun SettingsScreen(
    title: String,
    onOpenDrawer: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
    onOpenGeneral: () -> Unit = {},
    onOpenSim: (Int) -> Unit = {},
    onOpenSpamProtection: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SettingsScaffold(title = title, navigation = TopBarNavigation.Menu(onOpenDrawer)) {
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.section_general),
                supportingText = stringResource(R.string.general_summary),
                icon = Icons.Outlined.Settings,
                onClick = onOpenGeneral,
            )
            uiState.sims.forEach { sim ->
                SettingsItem(
                    title = sim.displayName,
                    // Carriers often leave the number out of the SIM; the page
                    // says so rather than showing an empty line.
                    supportingText = sim.number ?: stringResource(R.string.sim_number_unknown),
                    icon = Icons.Outlined.SimCard,
                    onClick = { onOpenSim(sim.subscriptionId) },
                )
            }
            SettingsItem(
                title = stringResource(R.string.section_spam),
                supportingText = stringResource(R.string.spam_summary),
                icon = Icons.Outlined.Security,
                onClick = onOpenSpamProtection,
            )
            SettingsItem(
                title = stringResource(R.string.section_advanced),
                supportingText = stringResource(R.string.advanced_summary),
                icon = Icons.Outlined.Tune,
                onClick = onOpenAdvanced,
            )
            SettingsItem(
                title = stringResource(R.string.section_about),
                supportingText = appVersion(context),
                icon = Icons.Outlined.Info,
                onClick = onOpenAbout,
            )
        }
    }
}

internal fun appVersion(context: Context): String {
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, locale = "fa")
@Composable
fun SettingsScreenPreview() {
    NoSpamTheme { SettingsScreen(title = "Settings") }
}

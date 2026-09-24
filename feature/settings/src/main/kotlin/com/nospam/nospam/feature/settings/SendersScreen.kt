// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.component.Avatar
import com.nospam.nospam.core.designsystem.component.SettingsGroup
import com.nospam.nospam.core.designsystem.component.SettingsItem
import com.nospam.nospam.core.designsystem.component.SettingsSectionHeader
import com.nospam.nospam.core.designsystem.component.TopBarNavigation
import com.nospam.nospam.core.designsystem.component.isolateIfPhoneNumber

/**
 * Every sender the user blocked or marked "Not spam", each with a way to undo
 * it. Undoing is not confirmed: blocking again, or marking "Not spam" again, is
 * one action away.
 */
@Composable
fun SendersScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: SendersViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.blocked_senders_title),
        navigation = TopBarNavigation.Back(onNavigateUp),
    ) {
        Text(
            stringResource(R.string.senders_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        SettingsSectionHeader(stringResource(R.string.senders_blocked))
        RuleGroup(
            rules = state.blocked,
            emptyText = stringResource(R.string.senders_blocked_empty),
            actionLabel = stringResource(R.string.senders_unblock),
            onAction = viewModel::unblock,
        )
        SettingsSectionHeader(stringResource(R.string.senders_allowed))
        RuleGroup(
            rules = state.allowed,
            emptyText = stringResource(R.string.senders_allowed_empty),
            actionLabel = stringResource(R.string.senders_remove),
            onAction = viewModel::removeAllow,
        )
    }
}

@Composable
private fun RuleGroup(
    rules: List<SenderRule>?,
    emptyText: String,
    actionLabel: String,
    onAction: (String) -> Unit,
) {
    // Still loading: say nothing rather than claim the list is empty.
    if (rules == null) return
    SettingsGroup {
        if (rules.isEmpty()) {
            SettingsItem(title = emptyText)
        }
        rules.forEach { rule ->
            val number = isolateIfPhoneNumber(rule.address)
            SettingsItem(
                title = rule.displayName ?: number,
                supportingText = if (rule.displayName != null) number else null,
                leadingContent = {
                    Avatar(name = rule.displayName ?: rule.address, colorKey = rule.address, photoUri = rule.photoUri)
                },
                trailingContent = {
                    TextButton(onClick = { onAction(rule.address) }) { Text(actionLabel) }
                },
            )
        }
    }
}

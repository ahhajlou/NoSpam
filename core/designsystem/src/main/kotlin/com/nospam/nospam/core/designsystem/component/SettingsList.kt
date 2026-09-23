// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Material's disabled content opacity. */
private const val DISABLED_ALPHA = 0.38f

/** Section title above a [SettingsGroup]. Announced as a heading. */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * Rounded group of settings rows separated by small gaps, in place of a
 * divider under every row. Outer corners use `extraLarge`, inner corners
 * `extraSmall`, so the group reads as one card made of rows.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.extraLarge),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * A settings row that navigates or opens a dialog. [onClick] null makes it
 * informational (no ripple, not focusable as a button).
 *
 * [enabled] false renders it at disabled opacity and ignores clicks. Use that
 * for settings whose storage does not exist yet, rather than a control that
 * looks live and does nothing.
 */
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    /** Replaces [icon] when the row leads with something richer, such as an avatar. */
    leadingContent: @Composable (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else Modifier
    SettingsListItem(
        title = title,
        supportingText = supportingText,
        icon = icon,
        enabled = enabled,
        trailingContent = trailingContent,
        leadingContent = leadingContent,
        modifier = modifier.then(clickModifier),
    )
}

/** A settings row whose whole surface toggles a switch. */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsListItem(
        title = title,
        supportingText = supportingText,
        icon = icon,
        enabled = enabled,
        // The row owns the toggle semantics; the switch is visual only, so
        // TalkBack announces one "switch, on/off" node rather than two.
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
private fun SettingsListItem(
    title: String,
    supportingText: String?,
    icon: ImageVector?,
    enabled: Boolean,
    trailingContent: @Composable (() -> Unit)?,
    modifier: Modifier,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    fun Color.orDisabled() = if (enabled) this else copy(alpha = DISABLED_ALPHA)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { { Text(it) } },
        leadingContent = leadingContent ?: icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(
            containerColor = colors.surfaceContainer,
            headlineColor = colors.onSurface.orDisabled(),
            supportingColor = colors.onSurfaceVariant.orDisabled(),
            leadingIconColor = colors.onSurfaceVariant.orDisabled(),
            trailingIconColor = colors.onSurfaceVariant.orDisabled(),
        ),
        modifier = modifier.clip(MaterialTheme.shapes.extraSmall),
    )
}

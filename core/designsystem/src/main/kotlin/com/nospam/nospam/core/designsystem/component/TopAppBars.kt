// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nospam.nospam.core.designsystem.R

/** What sits in the leading slot of a [NoSpamTopAppBar]. */
sealed interface TopBarNavigation {
    data object None : TopBarNavigation
    /** Top-level destinations reached from the drawer. */
    data class Menu(val onClick: () -> Unit) : TopBarNavigation
    /** Destinations pushed on top of another screen. */
    data class Back(val onClick: () -> Unit) : TopBarNavigation
}

/**
 * One action in a top app bar. The first `maxInline` actions of [TopBarActions]
 * render as icon buttons (label becomes the tooltip and content description);
 * the rest go to the overflow menu, where [icon] is optional.
 */
data class TopBarAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Small top app bar with a plain title. Each screen owns its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoSpamTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.None,
    actions: List<TopBarAction> = emptyList(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    NoSpamTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigation = navigation,
        actions = { TopBarActions(actions) },
        scrollBehavior = scrollBehavior,
    )
}

/** Slot variant, for titles that are more than text (avatar + name + subtitle). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoSpamTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.None,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = { NavigationButton(navigation) },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/**
 * Contextual top app bar shown while items are selected. It replaces the
 * screen's normal bar rather than stacking on it.
 *
 * The screen must also route system back to [onClearSelection] while selection
 * is active; this component does not install a back handler, so the owner
 * decides how back interacts with its own navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopAppBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    actions: List<TopBarAction> = emptyList(),
    maxInlineActions: Int = 2,
) {
    val resources = LocalResources.current
    TopAppBar(
        title = {
            AnimatedContent(targetState = selectedCount, label = "selectedCount") { count ->
                Text(resources.getQuantityString(R.plurals.ds_selected_count, count, count))
            }
        },
        modifier = modifier,
        navigationIcon = {
            TooltipIconButton(
                label = stringResource(R.string.ds_clear_selection),
                icon = Icons.Filled.Close,
                onClick = onClearSelection,
            )
        },
        actions = { TopBarActions(actions, maxInline = maxInlineActions) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

/**
 * Renders up to [maxInline] actions as icon buttons and the remainder in a
 * "More options" menu. Actions without an icon always go to the menu.
 * Material guidance caps a small top app bar at three trailing icons.
 */
@Composable
fun RowScope.TopBarActions(
    actions: List<TopBarAction>,
    maxInline: Int = 2,
) {
    val (inline, overflow) = partitionTopBarActions(actions, maxInline)
    inline.forEach { action ->
        TooltipIconButton(
            label = action.label,
            icon = action.icon!!,
            enabled = action.enabled,
            onClick = action.onClick,
        )
    }
    if (overflow.isNotEmpty()) {
        OverflowMenu(overflow)
    }
}

/** Pure split used by [TopBarActions]; icon-less actions can only overflow. */
internal fun partitionTopBarActions(
    actions: List<TopBarAction>,
    maxInline: Int,
): Pair<List<TopBarAction>, List<TopBarAction>> {
    val withIcon = actions.filter { it.icon != null }
    // Never more than maxInline icons, even when the rest would fit: the bar's
    // shape should not change with the number of actions.
    val inline = withIcon.take(maxInline)
    return inline to actions.filterNot { it in inline }
}

@Composable
private fun OverflowMenu(actions: List<TopBarAction>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            label = stringResource(R.string.ds_more_options),
            icon = Icons.Filled.MoreVert,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                val color = if (action.destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(action.label, color = if (action.enabled) color else color.copy(alpha = 0.38f)) },
                    leadingIcon = action.icon?.let { icon ->
                        { Icon(icon, contentDescription = null) }
                    },
                    enabled = action.enabled,
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun NavigationButton(navigation: TopBarNavigation) {
    when (navigation) {
        TopBarNavigation.None -> Unit
        is TopBarNavigation.Menu -> TooltipIconButton(
            label = stringResource(R.string.ds_open_navigation_menu),
            icon = Icons.Filled.Menu,
            onClick = navigation.onClick,
        )
        is TopBarNavigation.Back -> TooltipIconButton(
            label = stringResource(R.string.ds_navigate_up),
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = navigation.onClick,
        )
    }
}

/** Icon button whose label is both its content description and its long-press tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

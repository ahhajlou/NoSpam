// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nospam.nospam.R
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.navigation.LaunchTarget
import com.nospam.nospam.navigation.ArchivedRoute
import com.nospam.nospam.navigation.ConversationsRoute
import com.nospam.nospam.navigation.NoSpamNavHost
import com.nospam.nospam.navigation.SettingsRoute
import com.nospam.nospam.navigation.SpamRoute
import com.nospam.nospam.navigation.debugTools
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * App root: theme plus the navigation drawer. There is deliberately no
 * Scaffold or top app bar here — every destination owns its own, so a screen
 * can show its own title, a back button, an overflow menu or a selection bar.
 */
@Composable
fun NoSpamAppShell(
    launchTarget: LaunchTarget? = null,
    onLaunchTargetHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination

    NoSpamTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only drawer destinations can open it; a thread, the recipient
            // picker and onboarding use back navigation instead.
            gesturesEnabled = drawerState.isOpen || destination.isDrawerDestination(),
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        stringResource(R.string.drawer_messages),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 16.dp),
                    )
                    fun go(route: Any) {
                        scope.launch { drawerState.close() }
                        navController.navigateTopLevel(route)
                    }
                    TopLevelItem(R.string.drawer_inbox, Icons.Filled.Inbox, Icons.Outlined.Inbox,
                        destination.isOn(ConversationsRoute::class)) { go(ConversationsRoute) }
                    TopLevelItem(R.string.drawer_archived, Icons.Filled.Archive, Icons.Outlined.Archive,
                        destination.isOn(ArchivedRoute::class)) { go(ArchivedRoute) }
                    TopLevelItem(R.string.drawer_spam_blocked, Icons.Filled.Report, Icons.Outlined.Report,
                        destination.isOn(SpamRoute::class)) { go(SpamRoute) }
                    // Developer tools. Empty in release: the feature modules are
                    // debugImplementation, so nothing to show and nothing linked.
                    if (debugTools.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    }
                    debugTools.forEach { tool ->
                        TopLevelItem(tool.labelRes, tool.icon, tool.icon,
                            destination.isOnDebugTool(tool.routeTag)) {
                            scope.launch { drawerState.close() }
                            tool.navigate(navController)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    TopLevelItem(R.string.drawer_settings, Icons.Filled.Settings, Icons.Outlined.Settings,
                        destination.isOn(SettingsRoute::class)) { go(SettingsRoute) }
                    Spacer(modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        ) {
            NoSpamNavHost(
                navController = navController,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                launchTarget = launchTarget,
                onLaunchTargetHandled = onLaunchTargetHandled,
            )
        }
    }
}

@Composable
private fun TopLevelItem(
    labelRes: Int,
    selectedIcon: ImageVector,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(stringResource(labelRes)) },
        icon = { Icon(if (selected) selectedIcon else icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        // M3's default selected content is onSecondaryContainer, which in the
        // Stitch light palette is a mid grey: 4.56:1 on the selected pill
        // against 8.84:1 for every unselected row, so the current destination
        // was the *least* legible item in the drawer. onSurface keeps the pill
        // and restores the emphasis (13.3:1). Dark mode was already fine.
        colors = NavigationDrawerItemDefaults.colors(
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

private fun NavHostController.navigateTopLevel(route: Any) = navigate(route) {
    launchSingleTop = true
    popUpTo(graph.findStartDestination().id) { inclusive = false }
}

private fun NavDestination?.isOn(route: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(route) } == true

// Debug routes live in the debug source set, so they are matched by name here.
private fun NavDestination?.isOnDebugTool(routeTag: String): Boolean =
    this?.route?.contains(routeTag) == true

private fun NavDestination?.isDrawerDestination(): Boolean =
    isOn(ConversationsRoute::class) || isOn(ArchivedRoute::class) || isOn(SpamRoute::class) ||
        isOn(SettingsRoute::class) || debugTools.any { isOnDebugTool(it.routeTag) }

// Preview
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Shell Light")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Shell Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun NoSpamAppShellPreview() {
    NoSpamAppShell()
}

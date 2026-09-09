package com.nospam.nospam.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nospam.nospam.R
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.navigation.ArchivedRoute
import com.nospam.nospam.navigation.ConversationsRoute
import com.nospam.nospam.navigation.ExportRoute
import com.nospam.nospam.navigation.MlDebugRoute
import com.nospam.nospam.navigation.NoSpamNavHost
import com.nospam.nospam.navigation.SettingsRoute
import com.nospam.nospam.navigation.SpamRoute
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoSpamAppShell() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    NoSpamTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text(stringResource(R.string.drawer_messages), modifier = Modifier.padding(all = 16.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_inbox)) },
                        selected = currentRoute?.contains("Conversations") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(ConversationsRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Menu, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_archived)) },
                        selected = currentRoute?.contains("Archived") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(ArchivedRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Delete, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_spam_blocked)) },
                        selected = currentRoute?.contains("Spam") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(SpamRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Warning, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_export)) },
                        selected = currentRoute?.contains("Export") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(ExportRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Upload, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_mldebug)) },
                        selected = currentRoute?.contains("MlDebug") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(MlDebugRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Science, null) }
                    )
                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_mark_all_read)) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Filled.Warning, null) }
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_settings)) },
                        selected = currentRoute?.contains("Settings") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(SettingsRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Settings, null) }
                    )
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("NoSpam SMS") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.action_menu_desc))
                            }
                        }
                    )
                }
            ) { innerPadding ->
                androidx.compose.foundation.layout.Box(modifier = Modifier.padding(innerPadding)) {
                    NoSpamNavHost(navController = navController)
                }
            }
        }
    }
}

// Preview
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Shell Light")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Shell Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun NoSpamAppShellPreview() {
    NoSpamAppShell()
}

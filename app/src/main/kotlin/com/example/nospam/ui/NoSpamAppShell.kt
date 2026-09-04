package com.example.nospam.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nospam.core.designsystem.theme.NoSpamTheme
import com.example.nospam.navigation.ArchivedRoute
import com.example.nospam.navigation.ConversationsRoute
import com.example.nospam.navigation.NoSpamNavHost
import com.example.nospam.navigation.SettingsRoute
import com.example.nospam.navigation.SpamRoute
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
                    Text("Messages", modifier = Modifier.padding(all = 16.dp))
                    NavigationDrawerItem(
                        label = { Text("Inbox") },
                        selected = currentRoute?.contains("Conversations") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(ConversationsRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Menu, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Archived") },
                        selected = currentRoute?.contains("Archived") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(ArchivedRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Delete, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Spam & blocked") },
                        selected = currentRoute?.contains("Spam") == true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(SpamRoute) { launchSingleTop = true }
                        },
                        icon = { Icon(Icons.Filled.Warning, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Settings") },
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
                        title = { Text("NoSpam") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
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

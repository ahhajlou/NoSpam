package com.example.nospam.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.example.nospam.ui.screens.FavoritesScreen
import com.example.nospam.ui.screens.HomeScreen
import com.example.nospam.ui.screens.SettingsScreen
import com.example.nospam.navigation.AppDestinations

@PreviewScreenSizes
@Composable
fun NoSpamApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
//        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
//            Greeting(
//                name = "Android",
//                modifier = Modifier.padding(innerPadding)
//            )
//        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val modifier = Modifier.padding(innerPadding)

            // Switch view based on currentDestination
            when (currentDestination) {
                AppDestinations.HOME -> HomeScreen(modifier = modifier)
                AppDestinations.FAVORITES -> FavoritesScreen(modifier = modifier)
                AppDestinations.PROFILE -> SettingsScreen(modifier = modifier)
            }
        }
    }
}
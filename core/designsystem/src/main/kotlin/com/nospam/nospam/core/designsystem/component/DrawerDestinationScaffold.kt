package com.nospam.nospam.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Scaffold with a menu-button top app bar, for drawer destinations with no
 * other bar needs. A screen that swaps in a selection bar or adds overflow
 * actions builds its own Scaffold around [NoSpamTopAppBar] instead.
 *
 * Content receives the bar's insets already applied and consumed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerDestinationScaffold(
    title: String,
    onOpenDrawer: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = { NoSpamTopAppBar(title = title, navigation = TopBarNavigation.Menu(onOpenDrawer)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            content()
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.ui

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.nospam.nospam.navigation.ArchivedRoute
import com.nospam.nospam.navigation.ConversationsRoute
import com.nospam.nospam.navigation.OnboardingRoute
import com.nospam.nospam.navigation.SettingsRoute
import com.nospam.nospam.navigation.SpamRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * Back from a drawer destination returns to the inbox, and back from the inbox
 * leaves the app, as in Google Messages. Drawer pages never stack on each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerNavigationTest {

    private fun navController(start: Any) =
        NavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = start) {
                composable<OnboardingRoute> {}
                composable<ConversationsRoute> {}
                composable<ArchivedRoute> {}
                composable<SpamRoute> {}
                composable<SettingsRoute> {}
            }
        }

    private fun NavHostController.screens(): List<KClass<*>> {
        val routes = listOf(OnboardingRoute::class, ConversationsRoute::class, ArchivedRoute::class, SpamRoute::class, SettingsRoute::class)
        return currentBackStack.value.mapNotNull { entry -> routes.firstOrNull { entry.destination.hasRoute(it) } }
    }

    private fun NavHostController.visitEveryDrawerPage() {
        navigateTopLevel(ArchivedRoute)
        navigateTopLevel(SpamRoute)
        navigateTopLevel(SettingsRoute)
    }

    @Test fun `drawer pages do not stack when the app started on the inbox`() {
        val nav = navController(start = ConversationsRoute)
        nav.visitEveryDrawerPage()
        assertEquals(listOf(ConversationsRoute::class, SettingsRoute::class), nav.screens())
    }

    @Test fun `drawer pages do not stack after first-run onboarding`() {
        // A fresh install: the graph starts on onboarding, which replaces itself
        // with the inbox on completion.
        val nav = navController(start = OnboardingRoute)
        nav.navigate(ConversationsRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
        nav.visitEveryDrawerPage()
        assertEquals(listOf(ConversationsRoute::class, SettingsRoute::class), nav.screens())
    }

    @Test fun `choosing the inbox from the drawer returns to the one inbox`() {
        val nav = navController(start = ConversationsRoute)
        nav.visitEveryDrawerPage()
        nav.navigateTopLevel(ConversationsRoute)
        assertEquals(listOf(ConversationsRoute::class), nav.screens())
    }
}

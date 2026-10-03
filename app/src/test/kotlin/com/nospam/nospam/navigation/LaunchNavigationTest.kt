// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * Opening a conversation from a notification: a cold start begins on the
 * conversation itself, and leaving it still lands on the inbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchNavigationTest {

    private fun navController(start: Any) =
        NavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = start) {
                composable<ConversationsRoute> {}
                composable<ThreadRoute> {}
                composable<NewConversationRoute> {}
                composable<SettingsRoute> {}
            }
        }

    private fun NavHostController.screens(): List<KClass<*>> {
        val routes = listOf(ConversationsRoute::class, ThreadRoute::class, NewConversationRoute::class, SettingsRoute::class)
        return currentBackStack.value.mapNotNull { entry -> routes.firstOrNull { entry.destination.hasRoute(it) } }
    }

    @Test fun `leaving a conversation the app started on goes to the inbox`() {
        val nav = navController(start = ThreadRoute(5L, "+15550005"))
        nav.upOrInbox()
        assertEquals(listOf(ConversationsRoute::class), nav.screens())
    }

    @Test fun `leaving a conversation opened from the inbox goes back to that inbox`() {
        val nav = navController(start = ConversationsRoute)
        nav.navigate(ThreadRoute(5L))
        nav.upOrInbox()
        assertEquals(listOf(ConversationsRoute::class), nav.screens())
    }

    @Test fun `leaving a conversation opened from another screen goes back to that screen`() {
        val nav = navController(start = ThreadRoute(5L))
        nav.navigate(NewConversationRoute())
        nav.navigate(ThreadRoute(6L))
        nav.upOrInbox()
        assertEquals(listOf(ThreadRoute::class, NewConversationRoute::class), nav.screens())
    }

    @Test fun `the conversation on screen is recognised by its thread id`() {
        val nav = navController(start = ConversationsRoute)
        nav.navigate(ThreadRoute(5L, "+15550005"))
        val entry = nav.currentBackStackEntry!!
        assertTrue(entry.isThread(5L))
        assertFalse(entry.isThread(6L))
        nav.navigateUp()
        assertFalse(nav.currentBackStackEntry!!.isThread(5L))
    }

    private fun NavHostController.threadIds(): List<Long> =
        currentBackStack.value.filter { it.destination.hasRoute(ThreadRoute::class) }.map { it.toRoute<ThreadRoute>().threadId }

    @Test fun `a notification's conversation opens on the inbox, not on the conversation open before`() {
        val nav = navController(start = ConversationsRoute)
        nav.navigate(ThreadRoute(5L))
        nav.openOnInbox(ThreadRoute(6L))
        assertEquals(listOf(ConversationsRoute::class, ThreadRoute::class), nav.screens())
        assertEquals(listOf(6L), nav.threadIds())
        nav.upOrInbox()
        assertEquals(listOf(ConversationsRoute::class), nav.screens())
    }

    @Test fun `a notification's conversation leaves deeper screens behind`() {
        val nav = navController(start = ConversationsRoute)
        nav.navigate(SettingsRoute)
        nav.navigate(NewConversationRoute())
        nav.openOnInbox(ThreadRoute(6L))
        assertEquals(listOf(ConversationsRoute::class, ThreadRoute::class), nav.screens())
    }

    @Test fun `after starting on a conversation, the next notification's conversation still has the inbox below`() {
        val nav = navController(start = ThreadRoute(5L))
        nav.openOnInbox(ThreadRoute(6L))
        assertEquals(listOf(ConversationsRoute::class, ThreadRoute::class), nav.screens())
        assertEquals(listOf(6L), nav.threadIds())
    }

    @Test fun `opening from the inbox itself just adds the conversation`() {
        val nav = navController(start = ConversationsRoute)
        nav.openOnInbox(ThreadRoute(6L))
        assertEquals(listOf(ConversationsRoute::class, ThreadRoute::class), nav.screens())
    }

    @Test fun `shared text opens the recipient picker on the inbox, carrying the text`() {
        val nav = navController(start = ConversationsRoute)
        nav.navigate(ThreadRoute(5L))
        nav.openOnInbox(NewConversationRoute(forwardBody = "shared link"))
        assertEquals(listOf(ConversationsRoute::class, NewConversationRoute::class), nav.screens())
        assertEquals("shared link", nav.currentBackStackEntry!!.toRoute<NewConversationRoute>().forwardBody)
    }

    @Test fun `leaving the recipient picker the app started on goes to the inbox`() {
        val nav = navController(start = NewConversationRoute(forwardBody = "shared link"))
        nav.upOrInbox()
        assertEquals(listOf(ConversationsRoute::class), nav.screens())
    }
}

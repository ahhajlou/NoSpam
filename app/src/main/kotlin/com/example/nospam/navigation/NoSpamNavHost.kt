package com.example.nospam.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.example.nospam.NoSpamApplication
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.launch
import com.example.nospam.feature.conversations.ArchivedScreen
import com.example.nospam.feature.conversations.ConversationsScreen
import com.example.nospam.feature.conversations.SpamScreen
import com.example.nospam.feature.onboarding.OnboardingScreen
import com.example.nospam.feature.settings.SettingsScreen
import com.example.nospam.feature.thread.NewConversationScreen
import com.example.nospam.feature.thread.ThreadScreen
import kotlinx.serialization.Serializable

@Serializable object ConversationsRoute
@Serializable object ArchivedRoute
@Serializable object SpamRoute
@Serializable object SettingsRoute
@Serializable object OnboardingRoute
@Serializable object NewConversationRoute
@Serializable data class ThreadRoute(val threadId: Long)

@Composable
fun NoSpamNavHost(
    navController: NavHostController,
    startDestination: Any = ConversationsRoute,
    onDrawerClick: () -> Unit = {}
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<ConversationsRoute> {
            ConversationsScreen(
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onNewMessage = { navController.navigate(NewConversationRoute) }
            )
        }
        composable<ArchivedRoute> { ArchivedScreen(onConversationClick = { id -> navController.navigate(ThreadRoute(id)) }) }
        composable<SpamRoute> {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            SpamScreen(
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onNotSpam = { id ->
                    scope.launch {
                        val app = context.applicationContext as? NoSpamApplication ?: return@launch
                        app.container.spamRepository.markNotSpam(ThreadId(id))
                    }
                }
            )
        }
        composable<SettingsRoute> { SettingsScreen() }
        composable<OnboardingRoute> { OnboardingScreen(onComplete = { navController.navigate(ConversationsRoute) }) }
        composable<NewConversationRoute> { NewConversationScreen(onThreadCreated = { id -> navController.navigate(ThreadRoute(id)) }) }
        composable<ThreadRoute> { backStackEntry ->
            val args = backStackEntry.toRoute<ThreadRoute>()
            ThreadScreen(threadId = args.threadId)
        }
    }
}

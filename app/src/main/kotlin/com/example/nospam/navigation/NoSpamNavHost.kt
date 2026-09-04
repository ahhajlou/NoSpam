package com.example.nospam.navigation

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.example.nospam.NoSpamApplication
import com.example.nospam.core.model.ThreadId
import kotlinx.coroutines.launch
import com.example.nospam.feature.conversations.ArchivedScreen
import com.example.nospam.feature.conversations.ArchivedViewModel
import com.example.nospam.feature.conversations.ConversationsScreen
import com.example.nospam.feature.conversations.ConversationsViewModel
import com.example.nospam.feature.conversations.SpamScreen
import com.example.nospam.feature.conversations.SpamViewModel
import com.example.nospam.feature.onboarding.OnboardingScreen
import com.example.nospam.feature.settings.SettingsScreen
import com.example.nospam.feature.thread.NewConversationScreen
import com.example.nospam.feature.thread.ThreadScreen
import com.example.nospam.feature.thread.ThreadViewModel
import kotlinx.serialization.Serializable

@Serializable object ConversationsRoute
@Serializable object ArchivedRoute
@Serializable object SpamRoute
@Serializable object SettingsRoute
@Serializable object OnboardingRoute
@Serializable object NewConversationRoute
@Serializable data class ThreadRoute(val threadId: Long, val address: String? = null)

private inline fun <reified VM : ViewModel> vmFactory(crossinline create: () -> VM) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

/** First launch (or revoked state) lands on onboarding instead of an empty inbox. */
private fun needsOnboarding(context: Context): Boolean {
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED
    if (!smsGranted) return true
    return !isDefaultSmsApp(context)
}

private fun isDefaultSmsApp(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
}

@Composable
fun NoSpamNavHost(
    navController: NavHostController,
    startDestination: Any? = null,
    onDrawerClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? NoSpamApplication)?.container
    }
    val start = startDestination
        ?: if (container != null && needsOnboarding(context)) OnboardingRoute else ConversationsRoute

    NavHost(navController = navController, startDestination = start) {
        composable<ConversationsRoute> {
            val scope = rememberCoroutineScope()
            val vm: ConversationsViewModel = viewModel(
                factory = vmFactory {
                    container?.let { ConversationsViewModel(it.conversationsRepository) }
                        ?: ConversationsViewModel()
                }
            )
            ConversationsScreen(
                viewModel = vm,
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onNewMessage = { navController.navigate(NewConversationRoute) },
                onToggleRead = { id, read ->
                    scope.launch { container?.conversationsRepository?.setRead(ThreadId(id), read) }
                },
                onArchive = { id ->
                    scope.launch { container?.conversationsRepository?.archive(ThreadId(id)) }
                },
                onReportSpam = { id ->
                    scope.launch { container?.spamRepository?.markSpam(ThreadId(id)) }
                },
                onBlock = { address ->
                    scope.launch { container?.blocklistRepository?.block(address) }
                },
                onDelete = { id ->
                    scope.launch { container?.conversationsRepository?.deleteConversation(ThreadId(id)) }
                },
            )
        }
        composable<ArchivedRoute> {
            val scope = rememberCoroutineScope()
            val vm: ArchivedViewModel? = container?.let {
                viewModel(factory = vmFactory { ArchivedViewModel(it.conversationsRepository) })
            }
            ArchivedScreen(
                viewModel = vm,
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onUnarchive = { id ->
                    scope.launch { container?.conversationsRepository?.unarchive(ThreadId(id)) }
                },
                onDelete = { id ->
                    scope.launch { container?.conversationsRepository?.deleteConversation(ThreadId(id)) }
                },
            )
        }
        composable<SpamRoute> {
            val scope = rememberCoroutineScope()
            val vm: SpamViewModel? = container?.let {
                viewModel(factory = vmFactory { SpamViewModel(it.conversationsRepository) })
            }
            SpamScreen(
                viewModel = vm,
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onNotSpam = { id ->
                    scope.launch {
                        container?.spamRepository?.markNotSpam(ThreadId(id))
                    }
                },
                onBlock = { address ->
                    scope.launch { container?.blocklistRepository?.block(address) }
                },
                onDelete = { id ->
                    scope.launch { container?.conversationsRepository?.deleteConversation(ThreadId(id)) }
                },
            )
        }
        composable<SettingsRoute> { SettingsScreen() }
        composable<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(ConversationsRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                }
            )
        }
        composable<NewConversationRoute> {
            val scope = rememberCoroutineScope()
            NewConversationScreen(onAddressEntered = { address ->
                scope.launch {
                    // Resolve (or create) the provider thread, then open it with
                    // the address attached so sending works before any message
                    // exists. Without a container there is no provider access.
                    val threadId = container?.telephony?.getOrCreateThreadId(address) ?: -1L
                    navController.navigate(ThreadRoute(threadId, address))
                }
            })
        }
        composable<ThreadRoute> { backStackEntry ->
            val args = backStackEntry.toRoute<ThreadRoute>()
            val vm: ThreadViewModel = viewModel(
                factory = vmFactory {
                    container?.let { ThreadViewModel(it.telephony, args.address) } ?: ThreadViewModel()
                }
            )
            ThreadScreen(threadId = args.threadId, address = args.address, viewModel = vm)
        }
    }
}

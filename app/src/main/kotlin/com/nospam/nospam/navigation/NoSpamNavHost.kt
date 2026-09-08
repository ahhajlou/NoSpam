package com.nospam.nospam.navigation

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.core.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nospam.nospam.feature.conversations.ArchivedScreen
import com.nospam.nospam.feature.conversations.ArchivedViewModel
import com.nospam.nospam.feature.conversations.ConversationsScreen
import com.nospam.nospam.feature.conversations.ConversationsViewModel
import com.nospam.nospam.feature.conversations.SpamScreen
import com.nospam.nospam.feature.conversations.SpamViewModel
import com.nospam.nospam.feature.export.ExportScreen
import com.nospam.nospam.feature.export.ExportViewModel
import com.nospam.nospam.feature.onboarding.OnboardingScreen
import com.nospam.nospam.feature.settings.SettingsScreen
import com.nospam.nospam.feature.thread.NewConversationScreen
import com.nospam.nospam.feature.thread.ThreadScreen
import com.nospam.nospam.feature.thread.ThreadViewModel
import kotlinx.serialization.Serializable

@Serializable object ConversationsRoute
@Serializable object ArchivedRoute
@Serializable object SpamRoute
@Serializable object SettingsRoute
@Serializable object OnboardingRoute
@Serializable object NewConversationRoute
@Serializable object ExportRoute
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
    var resolvedStart by remember { mutableStateOf<Any?>(startDestination) }
    LaunchedEffect(context, container, startDestination) {
        if (startDestination != null) {
            resolvedStart = startDestination
        } else if (container == null) {
            resolvedStart = ConversationsRoute
        } else {
            val onboarding = withContext(Dispatchers.IO) { needsOnboarding(context) }
            resolvedStart = if (onboarding) OnboardingRoute else ConversationsRoute
        }
    }
    val start = resolvedStart
    if (start == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Hoisted ViewModel survives navigation (Inbox -> Settings -> Inbox).
    // Without this, each navigate() created a new NavBackStackEntry and a fresh
    // ViewModel whose init re-queried telephony (3.6s on SM-A730F). The repository
    // SharedFlow cache (30s replay) is the primary fix; hoisting is the safety net
    // so the composable reuses the same StateFlow and replays instantly.
    val conversationsVm: ConversationsViewModel = viewModel(
        factory = vmFactory {
            container?.let { ConversationsViewModel(it.conversationsRepository) }
                ?: ConversationsViewModel()
        }
    )
    val archivedVm: ArchivedViewModel? = container?.let {
        viewModel(factory = vmFactory { ArchivedViewModel(it.conversationsRepository) })
    }
    val spamVm: SpamViewModel? = container?.let {
        viewModel(factory = vmFactory { SpamViewModel(it.conversationsRepository) })
    }

    NavHost(navController = navController, startDestination = start) {
        composable<ConversationsRoute> {
            val scope = rememberCoroutineScope()
            ConversationsScreen(
                viewModel = conversationsVm,
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
            ArchivedScreen(
                viewModel = archivedVm,
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
            SpamScreen(
                viewModel = spamVm,
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
        composable<ExportRoute> {
            val vm: ExportViewModel = viewModel(
                factory = vmFactory {
                    container?.let {
                        ExportViewModel(it.telephony, it.database.messageVerdictDao)
                    } ?: ExportViewModel()
                }
            )
            ExportScreen(viewModel = vm)
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
            NewConversationScreen(
                onAddressEntered = { address ->
                    scope.launch {
                        val threadId = container?.telephony?.getOrCreateThreadId(address) ?: -1L
                        navController.navigate(ThreadRoute(threadId, address))
                    }
                },
                dataSource = container?.telephony
            )
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

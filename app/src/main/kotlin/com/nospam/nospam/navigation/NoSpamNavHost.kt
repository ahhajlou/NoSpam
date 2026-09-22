// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.R
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
import com.nospam.nospam.feature.onboarding.OnboardingScreen
import com.nospam.nospam.feature.onboarding.hasRequiredPermissions
import com.nospam.nospam.feature.settings.AboutSettingsScreen
import com.nospam.nospam.feature.settings.AdvancedSettingsScreen
import com.nospam.nospam.feature.settings.GeneralSettingsScreen
import com.nospam.nospam.feature.settings.SettingsScreen
import com.nospam.nospam.feature.settings.SettingsViewModel
import com.nospam.nospam.feature.settings.SimSettingsScreen
import com.nospam.nospam.feature.settings.SpamSettingsScreen
import com.nospam.nospam.feature.settings.SpamSettingsViewModel
import com.nospam.nospam.feature.thread.NewConversationScreen
import com.nospam.nospam.feature.thread.ThreadScreen
import com.nospam.nospam.feature.thread.ThreadViewModel
import kotlinx.serialization.Serializable

@Serializable object ConversationsRoute
@Serializable object ArchivedRoute
@Serializable object SpamRoute
@Serializable object SettingsRoute
@Serializable object SettingsGeneralRoute
@Serializable data class SettingsSimRoute(val subscriptionId: Int)
@Serializable object SettingsSpamRoute
@Serializable object SettingsAdvancedRoute
@Serializable object SettingsAboutRoute
@Serializable object OnboardingRoute
@Serializable data class NewConversationRoute(val forwardBody: String? = null)
@Serializable data class ThreadRoute(val threadId: Long, val address: String? = null, val forwardBody: String? = null)


/**
 * First launch, or a revoked permission, lands on onboarding instead of an
 * empty inbox.
 *
 * Checks the whole required list, not just READ_SMS: holding the default-SMS
 * role auto-grants the SMS permissions, so a READ_SMS-only check passed even
 * when contacts or phone had been denied, and the gate never appeared.
 * Notifications are deliberately not in that list, so turning them off does
 * not send anyone back here.
 */
private fun needsOnboarding(context: Context): Boolean {
    if (!hasRequiredPermissions(context)) return true
    return !isDefaultSmsApp(context)
}

private fun isDefaultSmsApp(context: Context): Boolean =
    com.nospam.nospam.core.telephony.DefaultSmsApp.isHeld(context)

@Composable
fun NoSpamNavHost(
    navController: NavHostController,
    startDestination: Any? = null,
    onOpenDrawer: () -> Unit = {},
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
    // Permissions can be revoked while the app is in the background. Android
    // usually kills the process, so the cold-start check above catches it — but
    // not always, and a surviving process would sit in the inbox with contacts
    // or SIM lookups silently returning nothing. Re-checking on resume closes
    // that, and also catches the default-SMS role being handed to another app.
    // Re-check the gate on every resume, and again once the graph exists.
    //
    // Resume alone is not enough. Revoking a permission kills the process; when
    // the user reopens the app the task is restored, the NavHost restores its
    // saved back stack (the inbox) rather than honouring `resolvedStart`, and at
    // that first resume `currentBackStackEntry` is still null, so a resume-only
    // check skips and never runs again. That left the app on the inbox with a
    // required permission missing — caught by tools/permission_gate_check.sh.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry, resumeTick) {
        val destination = currentEntry?.destination ?: return@LaunchedEffect
        if (destination.hasRoute(OnboardingRoute::class)) return@LaunchedEffect
        if (!needsOnboarding(context)) return@LaunchedEffect
        navController.navigate(OnboardingRoute) {
            // Nothing behind it: the inbox must not be reachable by back while a
            // required permission is missing.
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
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
            container?.let {
                ConversationsViewModel(
                    it.conversationsRepository,
                    backfillStatus = it.spamBackfill.status,
                    onCancelBackfill = { it.spamBackfill.cancel() },
                )
            } ?: ConversationsViewModel()
        }
    )
    val archivedVm: ArchivedViewModel? = container?.let {
        viewModel(factory = vmFactory { ArchivedViewModel(it.conversationsRepository) })
    }
    val spamVm: SpamViewModel? = container?.let {
        viewModel(factory = vmFactory { SpamViewModel(it.conversationsRepository) })
    }
    // Hoisted so the SIM list survives navigating into a SIM's page and back.
    val settingsVm: SettingsViewModel = viewModel(
        factory = vmFactory { SettingsViewModel(container?.telephony) }
    )

    NavHost(navController = navController, startDestination = start) {
        composable<ConversationsRoute> {
            val scope = rememberCoroutineScope()
            ConversationsScreen(
                title = stringResource(R.string.drawer_inbox),
                onOpenDrawer = onOpenDrawer,
                viewModel = conversationsVm,
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onNewMessage = { navController.navigate(NewConversationRoute()) },
                onSetRead = { id, read ->
                    scope.launch { container?.conversationsRepository?.setRead(ThreadId(id), read) }
                },
                onArchive = { id ->
                    scope.launch { container?.conversationsRepository?.archive(ThreadId(id)) }
                },
                onReportSpam = { id, address ->
                    scope.launch { container?.spamRepository?.markSpam(ThreadId(id), address) }
                },
                onBlock = { address ->
                    scope.launch { container?.blocklistRepository?.block(address) }
                },
                onUnblock = { address ->
                    scope.launch { container?.blocklistRepository?.unblock(address) }
                },
                onDelete = { id ->
                    scope.launch { container?.conversationsRepository?.deleteConversation(ThreadId(id)) }
                },
            )
        }
        composable<ArchivedRoute> {
            val scope = rememberCoroutineScope()
            ArchivedScreen(
                title = stringResource(R.string.drawer_archived),
                onOpenDrawer = onOpenDrawer,
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
                title = stringResource(R.string.drawer_spam_blocked),
                onOpenDrawer = onOpenDrawer,
                viewModel = spamVm,
                onConversationClick = { id -> navController.navigate(ThreadRoute(id)) },
                onNotSpam = { id, address ->
                    scope.launch {
                        container?.spamRepository?.markNotSpam(ThreadId(id), address)
                    }
                },
                onBlock = { address ->
                    scope.launch { container?.blocklistRepository?.block(address) }
                },
                onUnblock = { address ->
                    scope.launch { container?.blocklistRepository?.unblock(address) }
                },
                onDelete = { id ->
                    scope.launch { container?.conversationsRepository?.deleteConversation(ThreadId(id)) }
                },
            )
        }
        debugToolDestinations(container, context, onOpenDrawer)
        composable<SettingsRoute> {
            SettingsScreen(
                title = stringResource(R.string.drawer_settings),
                onOpenDrawer = onOpenDrawer,
                viewModel = settingsVm,
                onOpenGeneral = { navController.navigate(SettingsGeneralRoute) },
                onOpenSim = { subscriptionId -> navController.navigate(SettingsSimRoute(subscriptionId)) },
                onOpenSpamProtection = { navController.navigate(SettingsSpamRoute) },
                onOpenAdvanced = { navController.navigate(SettingsAdvancedRoute) },
                onOpenAbout = { navController.navigate(SettingsAboutRoute) },
            )
        }
        composable<SettingsGeneralRoute> {
            GeneralSettingsScreen(onNavigateUp = { navController.navigateUp() })
        }
        composable<SettingsSimRoute> { backStackEntry ->
            SimSettingsScreen(
                subscriptionId = backStackEntry.toRoute<SettingsSimRoute>().subscriptionId,
                onNavigateUp = { navController.navigateUp() },
                viewModel = settingsVm,
            )
        }
        composable<SettingsSpamRoute> {
            val vm: SpamSettingsViewModel = viewModel(
                factory = vmFactory { SpamSettingsViewModel(container?.settingsRepository) }
            )
            SpamSettingsScreen(onNavigateUp = { navController.navigateUp() }, viewModel = vm)
        }
        composable<SettingsAdvancedRoute> {
            AdvancedSettingsScreen(
                onNavigateUp = { navController.navigateUp() },
                onRecheck = { container?.spamBackfill?.rescanAll() },
            )
        }
        composable<SettingsAboutRoute> {
            AboutSettingsScreen(onNavigateUp = { navController.navigateUp() })
        }
        composable<OnboardingRoute> {
            val scope = rememberCoroutineScope()
            OnboardingScreen(
                onComplete = {
                    scope.launch {
                        container?.settingsRepository?.setBackfillPending(true)
                        container?.spamBackfill?.ensureStarted()
                    }
                    navController.navigate(ConversationsRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                }
            )
        }
        composable<NewConversationRoute> { backStackEntry ->
            val args = backStackEntry.toRoute<NewConversationRoute>()
            val scope = rememberCoroutineScope()
            NewConversationScreen(
                onNavigateUp = { navController.navigateUp() },
                onAddressEntered = { address ->
                    scope.launch {
                        val threadId = container?.telephony?.getOrCreateThreadId(address) ?: -1L
                        navController.navigate(ThreadRoute(threadId, address, args.forwardBody))
                    }
                },
                dataSource = container?.telephony
            )
        }
        composable<ThreadRoute> { backStackEntry ->
            val args = backStackEntry.toRoute<ThreadRoute>()
            val vm: ThreadViewModel = viewModel(
                factory = vmFactory {
                    container?.let {
                        ThreadViewModel(it.telephony, args.address, it.spamRepository, it.draftRepository)
                    } ?: ThreadViewModel()
                }
            )
            val scope = rememberCoroutineScope()
            ThreadScreen(
                threadId = args.threadId,
                address = args.address,
                forwardBody = args.forwardBody,
                onForward = { body -> navController.navigate(NewConversationRoute(forwardBody = body)) },
                onNavigateUp = { navController.navigateUp() },
                onArchive = { id ->
                    scope.launch { container?.conversationsRepository?.archive(ThreadId(id)) }
                },
                onBlock = { address ->
                    scope.launch { container?.blocklistRepository?.block(address) }
                },
                onDeleteConversation = { id ->
                    scope.launch { container?.conversationsRepository?.deleteConversation(ThreadId(id)) }
                },
                viewModel = vm,
            )
        }
    }
}

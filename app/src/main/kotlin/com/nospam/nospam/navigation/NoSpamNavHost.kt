// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import kotlinx.coroutines.flow.first
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nospam.nospam.NoSpamApplication
import com.nospam.nospam.R
import com.nospam.nospam.core.notifications.NotificationHelper
import com.nospam.nospam.core.model.Conversation
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
import com.nospam.nospam.feature.settings.GeneralSettingsViewModel
import com.nospam.nospam.feature.settings.SettingsScreen
import com.nospam.nospam.feature.settings.SettingsViewModel
import com.nospam.nospam.feature.settings.SimSettingsScreen
import com.nospam.nospam.feature.settings.SpamSettingsScreen
import com.nospam.nospam.feature.settings.SpamSettingsViewModel
import com.nospam.nospam.feature.settings.SendersScreen
import com.nospam.nospam.feature.settings.SendersViewModel
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
@Serializable object SettingsSendersRoute
@Serializable object SettingsAdvancedRoute
@Serializable object SettingsAboutRoute
@Serializable object OnboardingRoute
@Serializable data class NewConversationRoute(val forwardBody: String? = null)
@Serializable data class ThreadRoute(
    val threadId: Long,
    val address: String? = null,
    val forwardBody: String? = null,
    /** What the list knew about the other party, so the title is right from the first frame. */
    val contactName: String? = null,
    val contactPhotoUri: String? = null,
)

/**
 * The route for opening [threadId] from a list, carrying the other party as
 * that list already shows them. Without it the title started empty, became the
 * number and then the contact name while the thread loaded.
 */
internal fun threadRouteFrom(threadId: Long, shown: List<Conversation>?): ThreadRoute {
    val participant = shown?.firstOrNull { it.threadId.value == threadId }?.participants?.singleOrNull()
    return ThreadRoute(
        threadId,
        address = participant?.address,
        contactName = participant?.displayName,
        contactPhotoUri = participant?.photoUri,
    )
}

/** The conversation a [LaunchTarget] asks for. */
private suspend fun routeFor(target: LaunchTarget, container: com.nospam.nospam.AppContainer?): ThreadRoute =
    when (target) {
        is LaunchTarget.Thread -> ThreadRoute(target.threadId, target.address)
        is LaunchTarget.Compose -> {
            val threadId = container?.telephony?.getOrCreateThreadId(target.address) ?: -1L
            ThreadRoute(threadId, target.address, forwardBody = target.body)
        }
    }

/**
 * Opens a conversation another app or a notification asked for, directly on
 * top of the inbox: whatever was open before (another conversation, a settings
 * page) is left behind, so Back goes to the inbox, as in Google Messages and
 * Android's guidance for notifications. It used to open on top of the current
 * screen, so Back from a notification's conversation led to the conversation
 * open before it.
 */
internal fun NavHostController.openOnInbox(route: ThreadRoute) {
    // getBackStackEntry throws when the inbox is not on the back stack.
    val inboxOnStack = runCatching { getBackStackEntry<ConversationsRoute>() }.isSuccess
    if (!inboxOnStack) {
        // Started on a conversation (cold start from a notification): the inbox
        // goes in first, in place of everything.
        navigate(ConversationsRoute) { popUpTo(graph.id) { inclusive = true } }
    }
    navigate(route) { popUpTo<ConversationsRoute> { inclusive = false } }
}

/**
 * Up, or back, from a screen: to the screen below, or to the inbox when there
 * is none, as when the app started on a conversation from a notification.
 */
internal fun NavHostController.upOrInbox() {
    if (previousBackStackEntry != null) {
        navigateUp()
    } else {
        navigate(ConversationsRoute) { popUpTo(graph.id) { inclusive = true } }
    }
}

/** Whether [entry] already shows conversation [threadId]: a second tap must not stack a copy. */
internal fun NavBackStackEntry.isThread(threadId: Long): Boolean =
    destination.hasRoute(ThreadRoute::class) && toRoute<ThreadRoute>().threadId == threadId


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
    launchTarget: LaunchTarget? = null,
    onLaunchTargetHandled: () -> Unit = {},
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
            // A cold start for a conversation (a notification tap) starts on
            // that conversation, so the inbox is not drawn first and then
            // crossfaded away. Back from it still leads to the inbox, below.
            val opening = if (onboarding) null else launchTarget?.let { routeFor(it, container) }
            resolvedStart = when {
                onboarding -> OnboardingRoute
                opening != null -> opening
                else -> ConversationsRoute
            }
            if (opening != null) onLaunchTargetHandled()
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
        if (!withContext(Dispatchers.IO) { needsOnboarding(context) }) return@LaunchedEffect
        navController.navigate(OnboardingRoute) {
            // Nothing behind it: the inbox must not be reachable by back while a
            // required permission is missing. Pops the whole graph, not to its
            // start destination: when the start was onboarding it has already
            // left the stack, and popping to it would do nothing.
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    // A conversation an intent asked for: a notification tap, or another app's
    // "send SMS to". It waits while onboarding is showing or still required, so
    // the gate above always wins, then opens once on top of whatever is there;
    // back returns to it. Handled only after navigating, because clearing it
    // first would cancel this effect mid-lookup.
    LaunchedEffect(launchTarget, currentEntry) {
        val target = launchTarget ?: return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        if (entry.destination.hasRoute(OnboardingRoute::class)) return@LaunchedEffect
        if (withContext(Dispatchers.IO) { needsOnboarding(context) }) return@LaunchedEffect
        // Tapping the notification of the conversation already on screen: stay.
        if (target is LaunchTarget.Thread && entry.isThread(target.threadId)) {
            onLaunchTargetHandled()
            return@LaunchedEffect
        }
        navController.openOnInbox(routeFor(target, container))
        onLaunchTargetHandled()
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
                    swipeActions = it.settingsRepository.swipeActions,
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
        factory = vmFactory { SettingsViewModel(container?.telephony, container?.settingsRepository) }
    )

    NavHost(navController = navController, startDestination = start) {
        composable<ConversationsRoute> {
            val scope = rememberCoroutineScope()
            ConversationsScreen(
                title = stringResource(R.string.drawer_inbox),
                onOpenDrawer = onOpenDrawer,
                viewModel = conversationsVm,
                onConversationClick = { id ->
                    val shown = conversationsVm.uiState.value.let { it.pinned + it.conversations }
                    navController.navigate(threadRouteFrom(id, shown))
                },
                onNewMessage = { navController.navigate(NewConversationRoute()) },
                onSetRead = { ids, read ->
                    scope.launch { container?.conversationsRepository?.setRead(ids.map(::ThreadId), read) }
                    // Read means seen: their notifications go too, as when the
                    // conversation itself is opened.
                    if (read) ids.forEach { NotificationHelper.cancelNotification(context, it) }
                },
                onArchive = { ids ->
                    scope.launch { container?.conversationsRepository?.archive(ids.map(::ThreadId)) }
                },
                onReportSpam = { conversations ->
                    scope.launch {
                        container?.spamRepository?.markSendersSpam(conversations.map { (id, address) -> ThreadId(id) to address })
                    }
                },
                onBlock = { addresses ->
                    scope.launch { container?.blocklistRepository?.block(addresses) }
                },
                onUnblock = { addresses ->
                    scope.launch { container?.blocklistRepository?.unblock(addresses) }
                },
                onDelete = { ids ->
                    scope.launch { container?.conversationsRepository?.deleteConversations(ids.map(::ThreadId)) }
                },
                onUnarchive = { ids ->
                    scope.launch { container?.conversationsRepository?.unarchive(ids.map(::ThreadId)) }
                },
            )
        }
        composable<ArchivedRoute> {
            val scope = rememberCoroutineScope()
            ArchivedScreen(
                title = stringResource(R.string.drawer_archived),
                onOpenDrawer = onOpenDrawer,
                viewModel = archivedVm,
                onConversationClick = { id -> navController.navigate(threadRouteFrom(id, archivedVm?.conversations?.value)) },
                onUnarchive = { ids ->
                    scope.launch { container?.conversationsRepository?.unarchive(ids.map(::ThreadId)) }
                },
                onDelete = { ids ->
                    scope.launch { container?.conversationsRepository?.deleteConversations(ids.map(::ThreadId)) }
                },
            )
        }
        composable<SpamRoute> {
            val scope = rememberCoroutineScope()
            // Seen on the way in and again on the way out, so what arrives while
            // the page is open is not counted as new afterwards.
            DisposableEffect(Unit) {
                container?.markSpamSeen()
                onDispose { container?.markSpamSeen() }
            }
            SpamScreen(
                title = stringResource(R.string.drawer_spam_blocked),
                onOpenDrawer = onOpenDrawer,
                viewModel = spamVm,
                onConversationClick = { id -> navController.navigate(threadRouteFrom(id, spamVm?.conversations?.value)) },
                onNotSpam = { conversations ->
                    scope.launch {
                        container?.spamRepository?.markSendersNotSpam(conversations.map { (id, address) -> ThreadId(id) to address })
                    }
                },
                onBlock = { addresses ->
                    scope.launch { container?.blocklistRepository?.block(addresses) }
                },
                onUnblock = { addresses ->
                    scope.launch { container?.blocklistRepository?.unblock(addresses) }
                },
                onDelete = { ids ->
                    scope.launch { container?.conversationsRepository?.deleteConversations(ids.map(::ThreadId)) }
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
            val vm: GeneralSettingsViewModel = viewModel(
                factory = vmFactory { GeneralSettingsViewModel(container?.settingsRepository) }
            )
            GeneralSettingsScreen(onNavigateUp = { navController.navigateUp() }, viewModel = vm)
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
            SpamSettingsScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenSenders = { navController.navigate(SettingsSendersRoute) },
                viewModel = vm,
            )
        }
        composable<SettingsSendersRoute> {
            val vm: SendersViewModel = viewModel(
                factory = vmFactory {
                    SendersViewModel(container?.blocklistRepository, container?.spamRepository, container?.telephony)
                }
            )
            SendersScreen(onNavigateUp = { navController.navigateUp() }, viewModel = vm)
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
            OnboardingScreen(
                onComplete = {
                    container?.onSetupComplete()
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
                        ThreadViewModel(
                            it.telephony, args.address, it.spamRepository, it.draftRepository, it.settingsRepository,
                            onMessageQueued = { if (it.settingsRepository.messageSounds.first()) it.messageSounds.playSent() },
                        )
                    } ?: ThreadViewModel()
                }
            )
            val scope = rememberCoroutineScope()
            // Started on this conversation: back goes to the inbox, not out of the app.
            val isRoot = remember(backStackEntry) { navController.previousBackStackEntry == null }
            BackHandler(enabled = isRoot) { navController.upOrInbox() }
            ThreadScreen(
                threadId = args.threadId,
                address = args.address,
                forwardBody = args.forwardBody,
                contactName = args.contactName,
                contactPhotoUri = args.contactPhotoUri,
                onForward = { body -> navController.navigate(NewConversationRoute(forwardBody = body)) },
                onNavigateUp = { navController.upOrInbox() },
                onVisibilityChange = { id, visible ->
                    val holder = container?.visibleThread
                    // Only clear it if no other conversation took over meanwhile.
                    if (visible) holder?.value = id else holder?.compareAndSet(id, null)
                },
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

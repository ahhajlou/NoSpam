package com.nospam.nospam.feature.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ConversationFilter

@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel = viewModel(),
    onConversationClick: (Long) -> Unit = {},
    onNewMessage: () -> Unit = {},
    onToggleRead: (Long, Boolean) -> Unit = { _, _ -> },
    onArchive: (Long) -> Unit = {},
    onReportSpam: (Long, String) -> Unit = { _, _ -> },
    onBlock: (String) -> Unit = {},
    onDelete: (Long) -> Unit = {},
    onToggleStar: (Long) -> Unit = viewModel::toggleStar,
    onTogglePin: (Long) -> Unit = viewModel::togglePin,
    onToggleMute: (Long) -> Unit = viewModel::toggleMute,
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDefault by viewModel.isDefaultSmsApp.collectAsState()
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.checkDefaultSmsApp(context.applicationContext)
    }
    NoSpamTheme {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = onNewMessage, shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.start_chat), modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (!isDefault) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text("Not default SMS app — some features disabled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                item {
                    // Search bar
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        placeholder = { Text(stringResource(R.string.search_conversations)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                }
                item {
                    // Filter chips
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConversationFilter.entries.forEach { filter ->
                            val selected = uiState.filter == filter
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.onFilterSelected(filter) },
                                label = { Text(filterLabel(filter)) }
                            )
                        }
                    }
                }
                if (uiState.isLoading && uiState.pinned.isEmpty() && uiState.conversations.isEmpty()) {
                    items(8) { SkeletonRow() }
                } else {
                    if (uiState.pinned.isNotEmpty()) {
                        item {
                            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.section_pinned), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        items(uiState.pinned, key = { it.threadId.value }) { conv ->
                            ConversationRow(
                                conv,
                                onClick = { onConversationClick(conv.threadId.value) },
                                onLongClick = { menuFor = conv },
                            )
                        }
                        item { Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))) }
                        item {
                            Text(stringResource(R.string.section_recent), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    items(uiState.conversations, key = { it.threadId.value }) { conv ->
                        ConversationRow(
                            conv,
                            onClick = { onConversationClick(conv.threadId.value) },
                            onLongClick = { menuFor = conv },
                        )
                    }
                    if (uiState.pinned.isEmpty() && uiState.conversations.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    if (viewModel.isLive) stringResource(R.string.empty_title_live) else stringResource(R.string.empty_title),
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (viewModel.isLive) stringResource(R.string.empty_subtitle_live)
                                    else stringResource(R.string.empty_subtitle_new),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        menuFor?.let { conv ->
            val address = conv.participants.firstOrNull()?.address
            ConversationActionsDialog(
                title = conv.participants.firstOrNull()?.displayName ?: address
                    ?: stringResource(R.string.unknown_sender),
                actions = inboxActions(
                    conv = conv,
                    address = address,
                    onToggleRead = { onToggleRead(conv.threadId.value, !conv.read) },
                    onArchive = { onArchive(conv.threadId.value) },
                    onReportSpam = { onReportSpam(conv.threadId.value, address ?: "") },
                    onBlock = { address?.let(onBlock) },
                    onDelete = { onDelete(conv.threadId.value) },
                    onToggleStar = { onToggleStar(conv.threadId.value) },
                    onTogglePin = { onTogglePin(conv.threadId.value) },
                    onToggleMute = { onToggleMute(conv.threadId.value) },
                ) + buildList {
                    if (address != null) {
                        add(ConversationAction(label = "Add to contacts", onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                                    type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                                    putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, address)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }))
                        add(ConversationAction(label = "Call", onClick = {
                            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", address, null))) } catch (_: Exception) {}
                        }))
                    }
                },
                onDismiss = { menuFor = null },
            )
        }
    }
}

@Composable
private fun inboxActions(
    conv: Conversation,
    address: String?,
    onToggleRead: () -> Unit,
    onArchive: () -> Unit,
    onReportSpam: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
    onToggleStar: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
): List<ConversationAction> = buildList {
    add(
        ConversationAction(
            label = stringResource(
                if (conv.read) R.string.menu_mark_unread else R.string.menu_mark_read
            ),
            onClick = onToggleRead,
        )
    )
    add(ConversationAction(label = if (conv.isStarred) "Unstar" else "Star", onClick = onToggleStar))
    add(ConversationAction(label = if (conv.isPinned) "Unpin" else "Pin", onClick = onTogglePin))
    add(ConversationAction(label = if (conv.isMuted) "Unmute" else "Mute", onClick = onToggleMute))
    add(
        ConversationAction(
            label = stringResource(R.string.menu_archive),
            onClick = onArchive,
        )
    )
    add(
        ConversationAction(
            label = stringResource(R.string.menu_report_spam),
            destructive = true,
            onClick = onReportSpam,
        )
    )
    if (address != null) {
        add(
            ConversationAction(
                label = stringResource(
                    if (conv.isBlocked) R.string.menu_unblock else R.string.menu_block
                ),
                destructive = !conv.isBlocked,
                onClick = onBlock,
            )
        )
    }
    add(
        ConversationAction(
            label = stringResource(R.string.menu_delete),
            destructive = true,
            onClick = onDelete,
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp).height(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(conv.participants.firstOrNull()?.address?.take(1)?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (!conv.read) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error).align(Alignment.TopEnd))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(conv.participants.firstOrNull()?.displayName ?: conv.participants.firstOrNull()?.address ?: stringResource(R.string.unknown_sender), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (conv.isPinned) { Icon(Icons.Filled.PushPin, contentDescription = "Pinned", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                if (conv.isStarred) { Icon(Icons.Filled.Star, contentDescription = "Starred", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                if (conv.isMuted) { Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
                if (conv.spamState == com.nospam.nospam.core.model.ThreadSpamState.MIXED) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("Mixed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(formatTime(conv.date), style = MaterialTheme.typography.labelLarge, color = if (!conv.read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(conv.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SkeletonRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth(0.45f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
            Box(modifier = Modifier.fillMaxWidth(0.75f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
    }
}

@Composable
private fun filterLabel(filter: ConversationFilter): String = when (filter) {
    ConversationFilter.ALL -> stringResource(R.string.filter_all)
    ConversationFilter.UNREAD -> stringResource(R.string.filter_unread)
    ConversationFilter.KNOWN -> stringResource(R.string.filter_known)
    ConversationFilter.UNKNOWN -> stringResource(R.string.filter_unknown)
    ConversationFilter.STARRED -> stringResource(R.string.filter_starred)
}

@Composable
private fun formatTime(millis: Long): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = stringResource(R.string.time_now)
    val yesterday = stringResource(R.string.time_yesterday)
    return androidx.compose.runtime.remember(millis, now, yesterday) {
        val diff = System.currentTimeMillis() - millis
        val currentYear = isCurrentYear(millis)
        if (diff < -60_000) {
            val flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                if (currentYear) 0 else android.text.format.DateUtils.FORMAT_SHOW_YEAR
            android.text.format.DateUtils.formatDateTime(context, millis, flags)
        } else {
            when {
                diff < 60_000 -> now
                diff < 3600_000 -> "${diff / 60000}m"
                diff < 86400000 -> "${diff / 3600000}h"
                diff < 172800000 -> yesterday
                else -> {
                    val flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
                        android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                        if (currentYear) 0 else android.text.format.DateUtils.FORMAT_SHOW_YEAR
                    android.text.format.DateUtils.formatDateTime(context, millis, flags)
                }
            }
        }
    }
}

private fun isCurrentYear(millis: Long): Boolean {
    val calNow = java.util.Calendar.getInstance()
    val calThen = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR)
}

private fun isDefaultSmsApp(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
    } else {
        android.provider.Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
}

@Composable
fun ArchivedScreen(
    viewModel: ArchivedViewModel? = null,
    onConversationClick: (Long) -> Unit = {},
    onUnarchive: (Long) -> Unit = {},
    onDelete: (Long) -> Unit = {},
) {
    // Live data when a ViewModel is provided (empty until an archived-thread
    // store exists); fake seed for previews.
    val live = viewModel?.conversations?.collectAsState()?.value
    var fakeArchived by androidx.compose.runtime.remember(viewModel) {
        mutableStateOf(
            if (viewModel == null) listOf(
                Conversation(com.nospam.nospam.core.model.ThreadId(101), listOf(com.nospam.nospam.core.model.Participant("Bank Alerts")), "Your statement for account ending in 1234 is ready to view.", System.currentTimeMillis() - 86400000, 1, true, isArchived = true),
                Conversation(com.nospam.nospam.core.model.ThreadId(102), listOf(com.nospam.nospam.core.model.Participant("Home Depot")), "Your order #987654321 is ready for pickup.", System.currentTimeMillis() - 3 * 86400000, 1, true, isArchived = true),
            ) else emptyList()
        )
    }
    var archived = live ?: fakeArchived
    if (archived.isEmpty()) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.archive_empty_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.archive_empty_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        var menuFor by remember { mutableStateOf<Conversation?>(null) }
        fun unarchive(conv: Conversation) {
            // Live mode persists via the archive store (flow removes the row);
            // fake mode mutates its local seed.
            if (viewModel == null) {
                fakeArchived = fakeArchived.filterNot { it.threadId == conv.threadId }
            }
            onUnarchive(conv.threadId.value)
        }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(archived, key = { it.threadId.value }) { conv ->
                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    positionalThreshold = { it * 0.5f },
                    confirmValueChange = { value ->
                        if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart || value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) {
                            unarchive(conv)
                            true
                        } else false
                    }
                )
                androidx.compose.material3.SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.unarchive), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    },
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = false
                ) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                    ) {
                        ConversationRow(
                            conv,
                            onClick = { onConversationClick(conv.threadId.value) },
                            onLongClick = { menuFor = conv },
                        )
                    }
                }
            }
        }
        menuFor?.let { conv ->
            ConversationActionsDialog(
                title = conv.participants.firstOrNull()?.displayName
                    ?: conv.participants.firstOrNull()?.address
                    ?: stringResource(R.string.unknown_sender),
                actions = listOf(
                    ConversationAction(
                        label = stringResource(R.string.menu_unarchive),
                        onClick = { unarchive(conv) },
                    ),
                    ConversationAction(
                        label = stringResource(R.string.menu_delete),
                        destructive = true,
                        onClick = { onDelete(conv.threadId.value) },
                    ),
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpamScreen(
    viewModel: SpamViewModel? = null,
    onConversationClick: (Long) -> Unit = {},
    onNotSpam: (Long, String) -> Unit = { _, _ -> },
    onBlock: (String) -> Unit = {},
    onDelete: (Long) -> Unit = {},
) {
    // Live verdicts when a ViewModel is provided; fake seed for previews/tests.
    val live = viewModel?.conversations?.collectAsState()?.value
    val isLive = viewModel != null
    var fakeSpamList by androidx.compose.runtime.remember(viewModel) {
        mutableStateOf(
            if (isLive) emptyList()
            else listOf(
                Conversation(com.nospam.nospam.core.model.ThreadId(201), listOf(com.nospam.nospam.core.model.Participant("Win A Free Cruise!")), "Congratulations! You've been selected for an all-expenses-paid trip. Click here to claim.", System.currentTimeMillis(), 1, true, isSpam = true),
                Conversation(com.nospam.nospam.core.model.ThreadId(202), listOf(com.nospam.nospam.core.model.Participant("+1 (555) 928-1102")), "URGENT: Your account needs verification immediately or it will be suspended.", System.currentTimeMillis() - 86400000, 1, true, isSpam = true),
                Conversation(com.nospam.nospam.core.model.ThreadId(203), listOf(com.nospam.nospam.core.model.Participant("Crypto Alerts")), "Don't miss the next big pump! Join our exclusive VIP telegram group now.", System.currentTimeMillis() - 2 * 86400000, 1, true, isSpam = true),
            )
        )
    }
    // Session-dismissed ids for instant feedback; the persisted override also
    // removes the row via the live flow on the next emission.
    var dismissed by androidx.compose.runtime.remember(viewModel) { mutableStateOf(setOf<Long>()) }
    var showNotSpamSnack by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    fun markNotSpam(conv: Conversation, message: String) {
        if (isLive) dismissed = dismissed + conv.threadId.value
        else fakeSpamList = fakeSpamList.filterNot { it.threadId == conv.threadId }
        showNotSpamSnack = message
        onNotSpam(conv.threadId.value, conv.participants.firstOrNull()?.address.orEmpty())
    }
    val spamList = (live ?: fakeSpamList).filterNot { it.threadId.value in dismissed }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        // Banner
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.spam_banner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (spamList.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = {
                    spamList.forEach { c -> c.participants.firstOrNull()?.address?.let(onBlock) }
                }) { Text("Block all") }
                androidx.compose.material3.TextButton(onClick = {
                    spamList.forEach { c -> onDelete(c.threadId.value) }
                    if (!isLive) fakeSpamList = emptyList()
                }) { Text("Delete all") }
                if (!isLive) {
                    androidx.compose.material3.TextButton(onClick = { fakeSpamList = emptyList() }) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.empty_spam))
                    }
                }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(spamList, key = { it.threadId.value }) { conv ->
                // Resolved here (composable scope): confirmValueChange/onClick
                // lambdas below are not composable and can't call stringResource.
                val notSpamMessage = stringResource(R.string.marked_as_not_spam, conv.participants.first().address)
                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) {
                            markNotSpam(conv, notSpamMessage)
                            true
                        } else false
                    }
                )
                androidx.compose.material3.SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(stringResource(R.string.not_spam), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = false
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = { onConversationClick(conv.threadId.value) },
                                onLongClick = { menuFor = conv },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(conv.participants.first().address, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(formatTime(conv.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(conv.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            androidx.compose.material3.TextButton(onClick = { markNotSpam(conv, notSpamMessage) }) { Text(stringResource(R.string.not_spam)) }
                        }
                    }
                }
            }
        }
        showNotSpamSnack?.let {
            androidx.compose.material3.Snackbar(modifier = Modifier.padding(16.dp)) { Text(it) }
        }
        menuFor?.let { conv ->
            val address = conv.participants.firstOrNull()?.address
            val notSpamMessage = stringResource(R.string.marked_as_not_spam, address.orEmpty())
            ConversationActionsDialog(
                title = address ?: stringResource(R.string.unknown_sender),
                actions = buildList {
                    add(
                        ConversationAction(
                            label = stringResource(R.string.not_spam),
                            onClick = { markNotSpam(conv, notSpamMessage) },
                        )
                    )
                    if (address != null) {
                        add(
                            ConversationAction(
                                label = stringResource(
                                    if (conv.isBlocked) R.string.menu_unblock else R.string.menu_block
                                ),
                                destructive = !conv.isBlocked,
                                onClick = { onBlock(address) },
                            )
                        )
                    }
                    add(
                        ConversationAction(
                            label = stringResource(R.string.menu_delete),
                            destructive = true,
                            onClick = { onDelete(conv.threadId.value) },
                        )
                    )
                },
                onDismiss = { menuFor = null },
            )
        }
    }
}
// Previews for Phase 4.5
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Light EN")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "RTL FA", locale = "fa")
@Composable
fun ConversationsScreenPreview() {
    com.nospam.nospam.core.designsystem.theme.NoSpamTheme {
        ConversationsScreen()
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Archived Empty")
@Composable
fun ArchivedEmptyPreview() {
    com.nospam.nospam.core.designsystem.theme.NoSpamTheme {
        ArchivedScreen()
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Spam")
@Composable
fun SpamPreview() {
    com.nospam.nospam.core.designsystem.theme.NoSpamTheme {
        SpamScreen()
    }
}

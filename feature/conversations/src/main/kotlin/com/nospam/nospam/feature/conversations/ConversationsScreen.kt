// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.data.BackfillStatus
import com.nospam.nospam.core.designsystem.component.PruneSelection
import com.nospam.nospam.core.designsystem.component.SelectionState
import com.nospam.nospam.core.designsystem.component.TopBarAction
import com.nospam.nospam.core.designsystem.component.rememberSelectionState
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.core.model.ConversationFilter

private enum class InboxConfirm { DELETE, BLOCK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    title: String,
    onOpenDrawer: () -> Unit = {},
    viewModel: ConversationsViewModel = viewModel(),
    onConversationClick: (Long) -> Unit = {},
    onNewMessage: () -> Unit = {},
    // Multi-select actions hand over the whole selection in one call, so the
    // data layer can apply it as one write instead of one per conversation.
    onSetRead: (threadIds: List<Long>, read: Boolean) -> Unit = { _, _ -> },
    onArchive: (threadIds: List<Long>) -> Unit = {},
    onReportSpam: (conversations: List<Pair<Long, String>>) -> Unit = {},
    onBlock: (addresses: List<String>) -> Unit = {},
    onUnblock: (addresses: List<String>) -> Unit = {},
    onDelete: (threadIds: List<Long>) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDefault by viewModel.isDefaultSmsApp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.checkDefaultSmsApp(context.applicationContext) }

    val selection = rememberSelectionState()
    val all = uiState.pinned + uiState.conversations
    // While loading the list is empty for reasons unrelated to the selection.
    if (!uiState.isLoading) PruneSelection(selection, all.map { it.threadId.value })
    val selected = all.filter { it.threadId.value in selection.ids }
    val summary = summarize(selected)
    val ids = selected.map { it.threadId.value }
    var confirm by rememberSaveable { mutableStateOf<InboxConfirm?>(null) }

    fun act(block: () -> Unit) {
        block()
        selection.clear()
    }

    val actions = buildList {
        add(TopBarAction(
            label = stringResource(if (summary.allPinned) R.string.action_unpin else R.string.action_pin),
            icon = if (summary.allPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
        ) { act { viewModel.setPinned(ids, !summary.allPinned) } })
        add(TopBarAction(stringResource(R.string.menu_archive), Icons.Outlined.Archive) {
            act { onArchive(ids) }
        })
        add(TopBarAction(stringResource(R.string.menu_delete), Icons.Outlined.Delete) {
            confirm = InboxConfirm.DELETE
        })
        add(TopBarAction(
            label = stringResource(if (summary.anyUnread) R.string.menu_mark_read else R.string.menu_mark_unread),
            icon = if (summary.anyUnread) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
        ) { act { onSetRead(ids, summary.anyUnread) } })
        add(TopBarAction(
            label = stringResource(if (summary.allStarred) R.string.action_unstar else R.string.action_star),
            icon = if (summary.allStarred) Icons.Outlined.StarOutline else Icons.Outlined.Star,
        ) { act { viewModel.setStarred(ids, !summary.allStarred) } })
        add(TopBarAction(
            label = stringResource(if (summary.allMuted) R.string.action_unmute else R.string.action_mute),
            icon = if (summary.allMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
        ) { act { viewModel.setMuted(ids, !summary.allMuted) } })
        summary.singleAddress?.let { address ->
            add(TopBarAction(stringResource(R.string.action_add_contact), Icons.Outlined.PersonAdd) {
                act { addToContacts(context, address) }
            })
            add(TopBarAction(stringResource(R.string.action_call), Icons.Outlined.Call) {
                act { dial(context, address) }
            })
        }
        add(TopBarAction(stringResource(R.string.menu_report_spam), Icons.Outlined.Report, destructive = true) {
            act { onReportSpam(selected.map { it.threadId.value to it.participants.firstOrNull()?.address.orEmpty() }) }
        })
        if (summary.allBlocked) {
            add(TopBarAction(stringResource(R.string.menu_unblock), Icons.Outlined.Block) {
                act { onUnblock(addressesOf(selected)) }
            })
        } else {
            add(TopBarAction(stringResource(R.string.menu_block), Icons.Outlined.Block, destructive = true) {
                confirm = InboxConfirm.BLOCK
            })
        }
    }

    ConversationListScaffold(
        title = title,
        onOpenDrawer = onOpenDrawer,
        selection = selection,
        selectionActions = actions,
        floatingActionButton = {
            FloatingActionButton(onClick = onNewMessage) {
                Icon(Icons.Outlined.AddComment, contentDescription = stringResource(R.string.start_chat))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            // Insets go to contentPadding, not Modifier.padding: as padding they
            // clip the list at the bars, whereas edge-to-edge wants rows to
            // scroll behind them while first and last still come to rest clear.
            // The extra bottom is the FAB, which floats and is not in `padding`.
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + FAB_CLEARANCE,
            ),
        ) {
            if (!isDefault) {
                item(key = "banner-default") { NotDefaultBanner() }
            }
            val backfill = uiState.backfillProgress
            if (backfill is BackfillStatus.Running) {
                item(key = "banner-backfill") { BackfillBanner(backfill, onCancel = viewModel::cancelBackfill) }
            }
            item(key = "search") {
                InboxSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "filters") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConversationFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = uiState.filter == filter,
                            onClick = { viewModel.onFilterSelected(filter) },
                            label = { Text(filterLabel(filter)) },
                        )
                    }
                }
            }
            if (uiState.isLoading && all.isEmpty()) {
                items(8) { SkeletonRow() }
            } else {
                val open = { id: Long -> if (selection.isActive) selection.toggle(id) else onConversationClick(id) }
                if (uiState.pinned.isNotEmpty()) {
                    item(key = "header-pinned") { SectionHeader(stringResource(R.string.section_pinned)) }
                    items(uiState.pinned, key = { it.threadId.value }) { conv ->
                        val id = conv.threadId.value
                        ConversationRow(
                            conv = conv,
                            selected = id in selection.ids,
                            onClick = { open(id) },
                            onLongClick = { selection.toggle(id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (uiState.conversations.isNotEmpty()) {
                        item(key = "header-recent") { SectionHeader(stringResource(R.string.section_recent)) }
                    }
                }
                items(uiState.conversations, key = { it.threadId.value }) { conv ->
                    val id = conv.threadId.value
                    ConversationRow(
                        conv = conv,
                        selected = id in selection.ids,
                        onClick = { open(id) },
                        onLongClick = { selection.toggle(id) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (all.isEmpty()) {
                    item(key = "empty") {
                        EmptyListState(
                            icon = Icons.Outlined.Forum,
                            title = stringResource(if (viewModel.isLive) R.string.empty_title_live else R.string.empty_title),
                            subtitle = stringResource(if (viewModel.isLive) R.string.empty_subtitle_live else R.string.empty_subtitle_new),
                        )
                    }
                }
            }
        }
    }

    when (confirm) {
        InboxConfirm.DELETE -> ConfirmDeleteDialog(
            count = selected.size,
            onConfirm = { act { onDelete(ids) } },
            onDismiss = { confirm = null },
        )
        InboxConfirm.BLOCK -> ConfirmBlockDialog(
            count = addressesOf(selected).size,
            onConfirm = { act { onBlock(addressesOf(selected)) } },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

/**
 * Search input styled as a Material 3 search bar. It filters the list in
 * place rather than opening a separate search view, which is what the inbox
 * already did and what its tests and flows rely on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            placeholder = { Text(stringResource(R.string.search_conversations)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NotDefaultBanner() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.not_default_sms_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun BackfillBanner(backfill: BackfillStatus.Running, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.backfill_scanning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.backfill_count, backfill.processed, backfill.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.backfill_cancel)) }
        }
        val fraction = if (backfill.total == 0) 0f else backfill.processed.toFloat() / backfill.total
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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

/** FAB height plus its Scaffold margins, so the last row can scroll clear of it. */
private val FAB_CLEARANCE = 88.dp

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Light EN")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "RTL FA", locale = "fa")
@Composable
fun ConversationsScreenPreview() {
    NoSpamTheme {
        ConversationsScreen(title = "Inbox")
    }
}

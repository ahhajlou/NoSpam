// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.component.Avatar
import com.nospam.nospam.core.designsystem.component.ConfirmationDialog
import com.nospam.nospam.core.designsystem.component.isolateIfPhoneNumber
import com.nospam.nospam.core.designsystem.component.NoSpamTopAppBar
import com.nospam.nospam.core.designsystem.component.PruneSelection
import com.nospam.nospam.core.designsystem.component.SelectionTopAppBar
import com.nospam.nospam.core.designsystem.component.TopBarAction
import com.nospam.nospam.core.designsystem.component.TopBarActions
import com.nospam.nospam.core.designsystem.component.TopBarNavigation
import com.nospam.nospam.core.designsystem.component.rememberSelectionState
import com.nospam.nospam.core.designsystem.theme.MessageBubbleShapeIncoming
import com.nospam.nospam.core.designsystem.theme.MessageBubbleShapeOutgoing
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.DeliveryStatus
import com.nospam.nospam.core.model.isOutgoing
import kotlinx.coroutines.launch

/** Three icons plus the overflow, as in the conversation lists. */
private const val SELECTION_INLINE_ACTIONS = 3

private enum class ThreadConfirm { DELETE_MESSAGES, DELETE_CONVERSATION, BLOCK }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    address: String? = null,
    forwardBody: String? = null,
    onForward: (String) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onArchive: (Long) -> Unit = {},
    onBlock: (String) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    /** Reports when this conversation is (true) and stops being (false) on screen. */
    onVisibilityChange: (threadId: Long, visible: Boolean) -> Unit = { _, _ -> },
    viewModel: ThreadViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(threadId, address, forwardBody) { viewModel.loadThread(threadId, address, context, forwardBody) }
    // Resumed means the user can see it: a message arriving here then plays the
    // in-app sound instead of posting a notification.
    LifecycleResumeEffect(threadId) {
        onVisibilityChange(threadId, true)
        onPauseOrDispose { onVisibilityChange(threadId, false) }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lazyState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { lazyState.firstVisibleItemIndex == 0 } }
    // Scrolled to the oldest loaded message (end of a reverseLayout list) and more exist.
    val atOldestEndLoadMore by remember(uiState.hasMoreOlder) {
        derivedStateOf {
            val info = lazyState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            uiState.hasMoreOlder && info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(atOldestEndLoadMore, uiState.threadId) {
        if (atOldestEndLoadMore) viewModel.loadOlder()
    }
    var hasScrolledInitially by remember { mutableStateOf(false) }
    LaunchedEffect(threadId) { hasScrolledInitially = false }
    // Initial scroll must not depend on the atBottom race — use threadId + first non-empty
    LaunchedEffect(threadId, uiState.messages.isNotEmpty()) {
        if (!hasScrolledInitially && uiState.messages.isNotEmpty()) {
            lazyState.scrollToItem(0)
            hasScrolledInitially = true
        }
    }
    // Subsequent inbound while already at bottom auto-stick
    LaunchedEffect(uiState.messages.size) {
        if (hasScrolledInitially && atBottom && uiState.messages.isNotEmpty()) {
            lazyState.animateScrollToItem(0)
        }
    }

    val selection = rememberSelectionState()
    PruneSelection(selection, uiState.messages.map { it.id.value })
    BackHandler(enabled = selection.isActive) { selection.clear() }
    val selectedMessages = uiState.messages.filter { it.id.value in selection.ids }
    // Tap reveals a message's time; one at a time, as in Google Messages.
    var timestampFor by remember { mutableStateOf<Long?>(null) }
    var confirm by rememberSaveable { mutableStateOf<ThreadConfirm?>(null) }

    val title = uiState.contactName ?: (uiState.address ?: address)?.let(::isolateIfPhoneNumber) ?: ""
    val conversationActions = buildList {
        val target = uiState.address
        if (target != null) {
            add(TopBarAction(stringResource(R.string.action_call), Icons.Outlined.Call) { dial(context, target) })
            if (uiState.contactName == null) {
                add(TopBarAction(stringResource(R.string.action_add_contact), Icons.Outlined.PersonAdd) {
                    addToContacts(context, target)
                })
            }
        }
        add(TopBarAction(stringResource(R.string.action_archive), Icons.Outlined.Archive) {
            onArchive(threadId)
            onNavigateUp()
        })
        if (target != null) {
            add(TopBarAction(stringResource(R.string.action_block), Icons.Outlined.Block, destructive = true) {
                confirm = ThreadConfirm.BLOCK
            })
        }
        add(TopBarAction(stringResource(R.string.action_delete_conversation), Icons.Outlined.Delete, destructive = true) {
            confirm = ThreadConfirm.DELETE_CONVERSATION
        })
    }
    val messageActions = buildList {
        add(TopBarAction(stringResource(R.string.action_copy), Icons.Outlined.ContentCopy) {
            copyToClipboard(context, selectedMessages.joinToString("\n") { it.body })
            selection.clear()
        })
        val single = selectedMessages.singleOrNull()
        if (single != null) {
            add(TopBarAction(stringResource(R.string.action_forward), Icons.AutoMirrored.Outlined.Forward) {
                selection.clear()
                onForward(single.body)
            })
        }
        // Order matters: the first three icons stay in the bar, the rest move to
        // the ⋮ menu, and Delete belongs in the bar rather than Share.
        add(TopBarAction(stringResource(R.string.action_delete), Icons.Outlined.Delete, destructive = true) {
            confirm = ThreadConfirm.DELETE_MESSAGES
        })
        if (single != null) {
            add(TopBarAction(stringResource(R.string.action_share), Icons.Outlined.Share) {
                shareText(context, single.body)
                selection.clear()
            })
        }
    }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopAppBar(
                    selectedCount = selection.ids.size,
                    onClearSelection = selection::clear,
                    actions = messageActions,
                    maxInlineActions = SELECTION_INLINE_ACTIONS,
                )
            } else {
                NoSpamTopAppBar(
                    title = {
                        ThreadTitle(
                            title = title,
                            contactKnown = uiState.contactName != null,
                            address = uiState.address,
                            photoUri = uiState.contactPhotoUri,
                        )
                    },
                    navigation = TopBarNavigation.Back(onNavigateUp),
                    // Call stays in the bar; everything else lives in the ⋮ menu,
                    // so the title keeps its room even with a long contact name.
                    actions = { TopBarActions(conversationActions, maxInline = 1) },
                )
            }
        },
    ) { padding ->
        // Scaffold applies the bars' insets; the IME is not part of them, so it
        // is added after consuming, which keeps the compose bar above the keyboard
        // without double-counting the navigation bar underneath it.
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                val grouped = remember(uiState.messages) {
                    uiState.messages.groupBy {
                        java.time.Instant.ofEpochMilli(it.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    }.toSortedMap()
                }
                // Reverse the grouped map so the latest date group sits at the bottom (index 0)
                val reversedGrouped = remember(grouped) { grouped.entries.reversed() }
                val deliveredId = remember(uiState.messages) { newestDeliveredId(uiState.messages) }
                LazyColumn(
                    state = lazyState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout = true,
                ) {
                    reversedGrouped.forEach { (date, msgs) ->
                        // msgs are ASC; reverse within the group so the latest is at index 0
                        items(msgs.reversed(), key = { it.id.value }) { msg ->
                            val id = msg.id.value
                            MessageBubble(
                                msg = msg,
                                selected = id in selection.ids,
                                suspected = id in uiState.spamMessageIds && !msg.type.isOutgoing,
                                showTimestamp = timestampFor == id,
                                showDelivered = id == deliveredId,
                                onClick = {
                                    if (selection.isActive) selection.toggle(id)
                                    else timestampFor = if (timestampFor == id) null else id
                                },
                                onLongClick = { selection.toggle(id) },
                                onMarkNotSpam = { uiState.onMarkNotSpam?.invoke(id) },
                                onReportSpam = { uiState.onReportSpam?.invoke(id) },
                                onRetry = { viewModel.onRetry(id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        stickyHeader(key = "date-$date") { DateHeader(date) }
                    }
                    // Backward-pagination sentinel at the oldest end: loads the next
                    // older page when it comes into view (reverseLayout => last slot).
                    if (uiState.hasMoreOlder || uiState.loadingOlder) {
                        item(key = "load-older") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(if (uiState.loadingOlder) 40.dp else 1.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.loadingOlder) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                ComposeBar(
                    draft = uiState.draft,
                    onDraftChanged = viewModel::onDraftChanged,
                    onSend = viewModel::onSend,
                    sims = uiState.sims,
                    selectedSimId = uiState.selectedSimId,
                    onSimSelected = viewModel::onSimSelected,
                )
            }
            if (!atBottom) {
                FloatingActionButton(
                    onClick = { scope.launch { lazyState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = 72.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.jump_to_latest_desc))
                }
            }
        }
    }

    when (confirm) {
        ThreadConfirm.DELETE_MESSAGES -> ConfirmationDialog(
            title = resources.getQuantityString(R.plurals.delete_messages_title, selection.ids.size, selection.ids.size),
            text = stringResource(R.string.delete_messages_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.onDeleteMessages(selection.ids.toList())
                selection.clear()
            },
            onDismiss = { confirm = null },
        )
        ThreadConfirm.DELETE_CONVERSATION -> ConfirmationDialog(
            title = stringResource(R.string.delete_conversation_title),
            text = stringResource(R.string.delete_conversation_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDeleteConversation(threadId)
                onNavigateUp()
            },
            onDismiss = { confirm = null },
        )
        ThreadConfirm.BLOCK -> ConfirmationDialog(
            title = stringResource(R.string.block_sender_title),
            text = stringResource(R.string.block_sender_body),
            confirmLabel = stringResource(R.string.action_block),
            onConfirm = {
                uiState.address?.let(onBlock)
                onNavigateUp()
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

/** Avatar plus name, with the number underneath when the name came from contacts. */
@Composable
private fun ThreadTitle(title: String, contactKnown: Boolean, address: String?, photoUri: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(name = title, colorKey = address.orEmpty(), size = 36.dp, photoUri = photoUri)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contactKnown && address != null) {
                Text(
                    isolateIfPhoneNumber(address),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: Message,
    selected: Boolean,
    suspected: Boolean,
    showTimestamp: Boolean,
    showDelivered: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMarkNotSpam: () -> Unit,
    onReportSpam: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMe = msg.type.isOutgoing
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            // The highlight spans the row, so a selected message reads as selected
            // even when its bubble is narrow.
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
    ) {
        if (suspected) {
            Text(
                stringResource(R.string.suspected_spam),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onErrorContainer,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(colors.errorContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .clip(if (isMe) MessageBubbleShapeOutgoing else MessageBubbleShapeIncoming)
                .background(if (isMe) colors.primary else colors.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                msg.body,
                color = if (isMe) colors.onPrimary else colors.onSurface,
                // The message decides its own direction: an English message in a
                // Persian thread would otherwise have its punctuation moved.
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
        }
        when (msg.type) {
            MessageType.FAILED -> Text(
                stringResource(R.string.message_failed_retry),
                style = MaterialTheme.typography.labelMedium,
                color = colors.error,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(top = 2.dp),
            )
            MessageType.OUTBOX, MessageType.QUEUED -> Text(
                stringResource(R.string.message_sending),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            // Sent, and a delivery report was asked for. A failure is shown on
            // every message it happened to; "Delivered" only on the newest, as
            // Google Messages does, since the ones before it are implied.
            else -> when {
                msg.deliveryStatus == DeliveryStatus.FAILED -> Text(
                    stringResource(R.string.message_not_delivered),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
                showDelivered -> Text(
                    stringResource(R.string.message_delivered),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (showTimestamp) {
            Text(
                formatMessageTime(msg.date),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (suspected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMarkNotSpam) { Text(stringResource(R.string.action_not_spam)) }
                TextButton(onClick = onReportSpam) { Text(stringResource(R.string.action_report_spam)) }
            }
        }
    }
}

@Composable
private fun DateHeader(date: java.time.LocalDate) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            formatDateHeader(date),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Localized, unlike the previous hardcoded "Today" / "MMM d, yyyy". */
@Composable
private fun formatDateHeader(date: java.time.LocalDate): String {
    val context = LocalContext.current
    val today = java.time.LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.date_today)
        today.minusDays(1) -> stringResource(R.string.date_yesterday)
        else -> {
            val millis = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                if (date.year == today.year) 0 else android.text.format.DateUtils.FORMAT_SHOW_YEAR
            android.text.format.DateUtils.formatDateTime(context, millis, flags)
        }
    }
}

@Composable
private fun formatMessageTime(millis: Long): String {
    val context = LocalContext.current
    return remember(millis) {
        android.text.format.DateUtils.formatDateTime(context, millis, android.text.format.DateUtils.FORMAT_SHOW_TIME)
    }
}

internal fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    // The label is what the system clipboard UI shows for the entry, so it is
    // translated like anything else the user reads.
    val label = context.getString(R.string.clipboard_label)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

internal fun shareText(context: android.content.Context, text: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(android.content.Intent.createChooser(intent, null))
    }
}

internal fun dial(context: android.content.Context, address: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", address, null))
        )
    }
}

internal fun addToContacts(context: android.content.Context, address: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, address)
            }
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread Light")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread RTL", locale = "fa")
@Composable
fun ThreadScreenPreview() {
    NoSpamTheme {
        ThreadScreen(threadId = 1)
    }
}

/**
 * The newest outgoing message the network reported as delivered, which is the
 * one that gets the "Delivered" label; null when there is none.
 */
internal fun newestDeliveredId(messages: List<Message>): Long? =
    messages
        .filter { it.type.isOutgoing && it.deliveryStatus == DeliveryStatus.DELIVERED }
        .maxWithOrNull(compareBy<Message>({ it.date }, { it.id.value }))
        ?.id?.value

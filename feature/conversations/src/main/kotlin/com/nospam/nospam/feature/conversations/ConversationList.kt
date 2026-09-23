// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nospam.nospam.core.designsystem.component.Avatar
import com.nospam.nospam.core.designsystem.component.ConfirmationDialog
import com.nospam.nospam.core.designsystem.component.isolateIfPhoneNumber
import com.nospam.nospam.core.designsystem.component.NoSpamTopAppBar
import com.nospam.nospam.core.designsystem.component.SelectionState
import com.nospam.nospam.core.designsystem.component.SelectionTopAppBar
import com.nospam.nospam.core.designsystem.component.PruneSelection
import com.nospam.nospam.core.designsystem.component.TopBarAction
import com.nospam.nospam.core.designsystem.component.rememberSelectionState
import com.nospam.nospam.core.designsystem.component.TopBarNavigation
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.ThreadSpamState

/** Material guidance and Google Messages both keep three action icons before ⋮. */
private const val SELECTION_INLINE_ACTIONS = 3

/**
 * Scaffold shared by Inbox, Archived and Spam & blocked. While [selection] is
 * active the normal bar is replaced by the selection bar carrying
 * [selectionActions], and system back clears the selection instead of leaving
 * the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationListScaffold(
    title: String,
    onOpenDrawer: () -> Unit,
    selection: SelectionState,
    selectionActions: List<TopBarAction>,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(enabled = selection.isActive) { selection.clear() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.isActive) {
                SelectionTopAppBar(
                    selectedCount = selection.ids.size,
                    onClearSelection = selection::clear,
                    actions = selectionActions,
                    maxInlineActions = SELECTION_INLINE_ACTIONS,
                )
            } else {
                NoSpamTopAppBar(
                    title = title,
                    navigation = TopBarNavigation.Menu(onOpenDrawer),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = { if (!selection.isActive) floatingActionButton() },
        content = content,
    )
}

/**
 * One conversation. Tap opens it, or toggles it while selecting; long-press
 * starts selection. Unread is carried by weight and color rather than a dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    conv: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val participant = conv.participants.firstOrNull()
    val address = participant?.address.orEmpty()
    // A contact name keeps the layout's direction; a bare phone number is
    // isolated so a Persian inbox does not move its leading "+" to the wrong end.
    val name = participant?.displayName
        ?: participant?.address?.let(::isolateIfPhoneNumber)
        ?: stringResource(R.string.unknown_sender)
    val unread = !conv.read
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = participant?.displayName ?: address, colorKey = address, size = 48.dp, selected = selected)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Name and badge share the space the timestamp leaves; the name
                // ellipsizes before the badge or the time is ever pushed out.
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        fontWeight = if (unread) FontWeight.Bold else null,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        conv.isBlocked -> Badge(stringResource(R.string.badge_blocked), colors.errorContainer, colors.onErrorContainer)
                        conv.spamState == ThreadSpamState.MIXED ->
                            Badge(stringResource(R.string.badge_mixed), colors.tertiaryContainer, colors.onTertiaryContainer)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(conv.date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else null,
                    color = if (unread) colors.primary else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conv.snippet,
                    // An English message in a Persian inbox otherwise takes the
                    // layout's direction, which moves its full stop to the front.
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    fontWeight = if (unread) FontWeight.SemiBold else null,
                    color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusIcon(conv.isPinned, Icons.Filled.PushPin, R.string.section_pinned)
                StatusIcon(conv.isStarred, Icons.Filled.Star, R.string.filter_starred)
                StatusIcon(conv.isMuted, Icons.Filled.NotificationsOff, R.string.status_muted)
            }
        }
    }
}

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Spacer(Modifier.width(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusIcon(shown: Boolean, icon: ImageVector, labelRes: Int) {
    if (!shown) return
    Spacer(Modifier.width(6.dp))
    Icon(
        icon,
        contentDescription = stringResource(labelRes),
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Placeholder rows while a list's first load is in flight, the same rows the
 * inbox shows, so a page never claims to be empty before it knows.
 */
@Composable
internal fun LoadingList(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        userScrollEnabled = false,
    ) {
        items(8) { SkeletonRow() }
    }
}

@Composable
internal fun SkeletonRow() {
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(placeholder))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.45f).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(placeholder))
            Box(Modifier.fillMaxWidth(0.75f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(placeholder))
        }
    }
}

@Composable
internal fun EmptyListState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Hard delete from the SMS provider: always confirmed, always with the count. */
@Composable
internal fun ConfirmDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    ConfirmationDialog(
        title = resources.getQuantityString(R.plurals.confirm_delete_title, count, count),
        text = stringResource(R.string.confirm_delete_body),
        confirmLabel = stringResource(R.string.menu_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * A block reaches beyond the app while NoSpam holds the SMS role (it is
 * written to the system blocked-numbers list), so the dialog says so.
 */
@Composable
internal fun ConfirmBlockDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    ConfirmationDialog(
        title = resources.getQuantityString(R.plurals.confirm_block_title, count, count),
        text = stringResource(R.string.confirm_block_body),
        confirmLabel = stringResource(R.string.menu_block),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
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

/**
 * Row timestamp: minutes, hours, "Yesterday", then a date.
 *
 * The minute and hour forms go through plurals rather than string templates:
 * "${'$'}{diff / 60_000}m" was English either way and always wrote Latin digits, so a
 * Persian inbox showed "5m" next to Persian text. `getQuantityString` formats
 * with the current locale, which also gives Persian digits.
 */
@Composable
internal fun formatTime(millis: Long): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val now = stringResource(R.string.time_now)
    val yesterday = stringResource(R.string.time_yesterday)
    return remember(millis, now, yesterday, resources) {
        val diff = System.currentTimeMillis() - millis
        val currentYear = isCurrentYear(millis)
        val dateFlags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
            if (currentYear) 0 else android.text.format.DateUtils.FORMAT_SHOW_YEAR
        when {
            diff < -60_000 -> android.text.format.DateUtils.formatDateTime(context, millis, dateFlags)
            diff < 60_000 -> now
            diff < 3_600_000 -> {
                val minutes = (diff / 60_000).toInt()
                resources.getQuantityString(R.plurals.time_minutes, minutes, minutes)
            }
            diff < 86_400_000 -> {
                val hours = (diff / 3_600_000).toInt()
                resources.getQuantityString(R.plurals.time_hours, hours, hours)
            }
            diff < 172_800_000 -> yesterday
            else -> android.text.format.DateUtils.formatDateTime(context, millis, dateFlags)
        }
    }
}

private fun isCurrentYear(millis: Long): Boolean {
    val calNow = java.util.Calendar.getInstance()
    val calThen = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR)
}

/**
 * [ConversationRow] with one swipe action toward the end edge (Unarchive,
 * Not spam). Swiping is off while selecting, so a drag cannot act on a row
 * the user is trying to select.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableConversationRow(
    conv: Conversation,
    selected: Boolean,
    swipeEnabled: Boolean,
    swipeLabel: String,
    swipeIcon: ImageVector,
    onSwiped: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState()
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        gesturesEnabled = swipeEnabled,
        onDismiss = { onSwiped() },
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(swipeIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(swipeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
    ) {
        ConversationRow(
            conv = conv,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        )
    }
}

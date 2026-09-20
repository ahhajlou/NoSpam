// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nospam.nospam.core.designsystem.component.PruneSelection
import com.nospam.nospam.core.designsystem.component.SelectionState
import com.nospam.nospam.core.designsystem.component.TopBarAction
import com.nospam.nospam.core.designsystem.component.rememberSelectionState
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId

@Composable
fun ArchivedScreen(
    title: String,
    onOpenDrawer: () -> Unit = {},
    viewModel: ArchivedViewModel? = null,
    onConversationClick: (Long) -> Unit = {},
    onUnarchive: (Long) -> Unit = {},
    onDelete: (Long) -> Unit = {},
) {
    // Live data when a ViewModel is provided; a local seed for previews and tests.
    val live = viewModel?.conversations?.collectAsStateWithLifecycle()?.value
    var fake by remember(viewModel) { mutableStateOf(if (viewModel == null) fakeArchived() else emptyList()) }
    val archived = live ?: fake
    fun unarchive(id: Long) {
        if (viewModel == null) fake = fake.filterNot { it.threadId.value == id }
        onUnarchive(id)
    }
    fun delete(id: Long) {
        if (viewModel == null) fake = fake.filterNot { it.threadId.value == id }
        onDelete(id)
    }

    val selection = rememberSelectionState()
    PruneSelection(selection, archived.map { it.threadId.value })
    val ids = archived.map { it.threadId.value }.filter { it in selection.ids }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val actions = listOf(
        TopBarAction(stringResource(R.string.menu_unarchive), Icons.Outlined.Unarchive) {
            ids.forEach(::unarchive)
            selection.clear()
        },
        TopBarAction(stringResource(R.string.menu_delete), Icons.Outlined.Delete) { confirmDelete = true },
    )

    ConversationListScaffold(
        title = title,
        onOpenDrawer = onOpenDrawer,
        selection = selection,
        selectionActions = actions,
    ) { padding ->
        if (archived.isEmpty()) {
            EmptyListState(
                icon = Icons.Outlined.Archive,
                title = stringResource(R.string.archive_empty_title),
                subtitle = stringResource(R.string.archive_empty_subtitle),
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            ) {
                items(archived, key = { it.threadId.value }) { conv ->
                    val id = conv.threadId.value
                    SwipeableConversationRow(
                        conv = conv,
                        selected = id in selection.ids,
                        swipeEnabled = !selection.isActive,
                        swipeLabel = stringResource(R.string.unarchive),
                        swipeIcon = Icons.Outlined.Unarchive,
                        onSwiped = { unarchive(id) },
                        onClick = { if (selection.isActive) selection.toggle(id) else onConversationClick(id) },
                        onLongClick = { selection.toggle(id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            count = ids.size,
            onConfirm = {
                ids.forEach(::delete)
                selection.clear()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun fakeArchived(): List<Conversation> = listOf(
    Conversation(ThreadId(101), listOf(Participant("Bank Alerts")), "Your statement for account ending in 1234 is ready to view.", System.currentTimeMillis() - 86_400_000, 1, true, isArchived = true),
    Conversation(ThreadId(102), listOf(Participant("Home Depot")), "Your order #987654321 is ready for pickup.", System.currentTimeMillis() - 3 * 86_400_000, 1, true, isArchived = true),
)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Archived")
@Composable
fun ArchivedPreview() {
    NoSpamTheme { ArchivedScreen(title = "Archived") }
}

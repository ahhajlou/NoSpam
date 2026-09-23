// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.launch

private enum class SpamConfirm { DELETE, BLOCK }

@Composable
fun SpamScreen(
    title: String,
    onOpenDrawer: () -> Unit = {},
    viewModel: SpamViewModel? = null,
    onConversationClick: (Long) -> Unit = {},
    // Whole selections in one call; a swipe passes a list of one.
    onNotSpam: (conversations: List<Pair<Long, String>>) -> Unit = {},
    onBlock: (addresses: List<String>) -> Unit = {},
    onUnblock: (addresses: List<String>) -> Unit = {},
    onDelete: (threadIds: List<Long>) -> Unit = {},
) {
    // Live verdicts when a ViewModel is provided; a local seed for previews and tests.
    val live = viewModel?.conversations?.collectAsStateWithLifecycle()?.value
    var fake by remember(viewModel) { mutableStateOf(if (viewModel == null) fakeSpam() else emptyList()) }
    // Rows marked not spam this session disappear at once; the persisted
    // override removes them from the live flow on its next emission.
    var dismissed by remember(viewModel) { mutableStateOf(setOf<Long>()) }
    val loading = viewModel != null && live == null
    val spam = (live ?: fake).filterNot { it.threadId.value in dismissed }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    fun notSpam(conversations: List<Conversation>) {
        val moved = conversations.map { it.threadId.value }.toSet()
        if (viewModel == null) fake = fake.filterNot { it.threadId.value in moved } else dismissed = dismissed + moved
        onNotSpam(conversations.map { it.threadId.value to it.participants.firstOrNull()?.address.orEmpty() })
        val message = resources.getQuantityString(R.plurals.marked_not_spam_count, moved.size, moved.size)
        scope.launch { snackbar.showSnackbar(message) }
    }
    fun delete(threadIds: List<Long>) {
        if (viewModel == null) fake = fake.filterNot { it.threadId.value in threadIds }
        onDelete(threadIds)
    }

    val selection = rememberSelectionState()
    // While loading, the list is empty for reasons unrelated to the selection.
    if (!loading) PruneSelection(selection, spam.map { it.threadId.value })
    val selected = spam.filter { it.threadId.value in selection.ids }
    val summary = summarize(selected)
    var confirm by rememberSaveable { mutableStateOf<SpamConfirm?>(null) }

    val actions = buildList {
        add(TopBarAction(stringResource(R.string.not_spam), Icons.Outlined.MoveToInbox) {
            notSpam(selected)
            selection.clear()
        })
        if (summary.allBlocked) {
            add(TopBarAction(stringResource(R.string.menu_unblock), Icons.Outlined.Block) {
                onUnblock(addressesOf(selected))
                selection.clear()
            })
        } else {
            add(TopBarAction(stringResource(R.string.menu_block), Icons.Outlined.Block) { confirm = SpamConfirm.BLOCK })
        }
        add(TopBarAction(stringResource(R.string.menu_delete), Icons.Outlined.Delete) { confirm = SpamConfirm.DELETE })
    }

    ConversationListScaffold(
        title = title,
        onOpenDrawer = onOpenDrawer,
        selection = selection,
        selectionActions = actions,
        snackbarHostState = snackbar,
    ) { padding ->
        if (loading) {
            LoadingList(padding)
        } else if (spam.isEmpty()) {
            EmptyListState(
                icon = Icons.Outlined.GppGood,
                title = stringResource(R.string.spam_empty_title),
                subtitle = stringResource(R.string.spam_empty_subtitle),
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            ) {
                items(spam, key = { it.threadId.value }) { conv ->
                    val id = conv.threadId.value
                    SwipeableConversationRow(
                        conv = conv,
                        selected = id in selection.ids,
                        swipeEnabled = !selection.isActive,
                        swipeLabel = stringResource(R.string.not_spam),
                        swipeIcon = Icons.Outlined.MoveToInbox,
                        onSwiped = { notSpam(listOf(conv)) },
                        onClick = { if (selection.isActive) selection.toggle(id) else onConversationClick(id) },
                        onLongClick = { selection.toggle(id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    when (confirm) {
        SpamConfirm.DELETE -> ConfirmDeleteDialog(
            count = selected.size,
            onConfirm = {
                delete(selected.map { it.threadId.value })
                selection.clear()
            },
            onDismiss = { confirm = null },
        )
        SpamConfirm.BLOCK -> ConfirmBlockDialog(
            count = addressesOf(selected).size,
            onConfirm = {
                onBlock(addressesOf(selected))
                selection.clear()
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

private fun fakeSpam(): List<Conversation> = listOf(
    Conversation(ThreadId(201), listOf(Participant("Win A Free Cruise!")), "Congratulations! You've been selected for an all-expenses-paid trip. Click here to claim.", System.currentTimeMillis(), 1, true, isSpam = true),
    Conversation(ThreadId(202), listOf(Participant("+1 (555) 928-1102")), "URGENT: Your account needs verification immediately or it will be suspended.", System.currentTimeMillis() - 86_400_000, 1, true, isSpam = true),
    Conversation(ThreadId(203), listOf(Participant("Crypto Alerts")), "Don't miss the next big pump! Join our exclusive VIP telegram group now.", System.currentTimeMillis() - 2 * 86_400_000, 1, true, isSpam = true, isBlocked = true),
)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Spam")
@Composable
fun SpamPreview() {
    NoSpamTheme { SpamScreen(title = "Spam & blocked") }
}

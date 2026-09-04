package com.example.nospam.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nospam.core.designsystem.theme.NoSpamTheme
import com.example.nospam.core.model.Conversation
import com.example.nospam.core.model.ConversationFilter

@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel = viewModel(),
    onConversationClick: (Long) -> Unit = {},
    onNewMessage: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    NoSpamTheme {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = onNewMessage, shape = RoundedCornerShape(16.dp)) {
                    Text("Start chat", modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    // Search bar
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        placeholder = { Text("Search conversations") },
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
                                label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
                if (uiState.pinned.isNotEmpty()) {
                    item {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Pinned", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(uiState.pinned, key = { it.threadId.value }) { conv ->
                        ConversationRow(conv, onClick = { onConversationClick(conv.threadId.value) })
                    }
                    item { Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))) }
                    item {
                        Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                items(uiState.conversations, key = { it.threadId.value }) { conv ->
                    ConversationRow(conv, onClick = { onConversationClick(conv.threadId.value) })
                }
                if (uiState.pinned.isEmpty() && uiState.conversations.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (viewModel.isLive) "No conversations yet" else "No conversations",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (viewModel.isLive) "Messages you receive will appear here."
                                else "Start a new chat below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conv: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).height(72.dp),
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(conv.participants.firstOrNull()?.displayName ?: conv.participants.firstOrNull()?.address ?: "Unknown", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatTime(conv.date), style = MaterialTheme.typography.labelLarge, color = if (!conv.read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(conv.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60000}m"
        diff < 86400000 -> "${diff / 3600000}h"
        diff < 172800000 -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

@Composable
fun ArchivedScreen(
    viewModel: ArchivedViewModel? = null,
    onConversationClick: (Long) -> Unit = {},
) {
    // Live data when a ViewModel is provided (empty until an archived-thread
    // store exists); fake seed for previews.
    val live = viewModel?.conversations?.collectAsState()?.value
    var fakeArchived by androidx.compose.runtime.remember(viewModel) {
        mutableStateOf(
            if (viewModel == null) listOf(
                Conversation(com.example.nospam.core.model.ThreadId(101), listOf(com.example.nospam.core.model.Participant("Bank Alerts")), "Your statement for account ending in 1234 is ready to view.", System.currentTimeMillis() - 86400000, 1, true, isArchived = true),
                Conversation(com.example.nospam.core.model.ThreadId(102), listOf(com.example.nospam.core.model.Participant("Home Depot")), "Your order #987654321 is ready for pickup.", System.currentTimeMillis() - 3 * 86400000, 1, true, isArchived = true),
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
            Text("Archive is empty", style = MaterialTheme.typography.headlineMedium)
            Text("Messages you archive will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        // Live mode has no archive store yet, so unarchive only dismisses for
        // this session; fake mode mutates its local seed.
        var liveDismissed by androidx.compose.runtime.remember(viewModel) {
            mutableStateOf(setOf<Long>())
        }
        val visible = archived.filterNot { it.threadId.value in liveDismissed }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.threadId.value }) { conv ->
                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    positionalThreshold = { it * 0.5f },
                    confirmValueChange = { value ->
                        if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart || value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) {
                            if (viewModel == null) {
                                fakeArchived = fakeArchived.filterNot { it.threadId == conv.threadId }
                            } else {
                                liveDismissed = liveDismissed + conv.threadId.value
                            }
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
                                Text("Unarchive", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    },
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = false
                ) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).clickable { onConversationClick(conv.threadId.value) }
                    ) {
                        ConversationRow(conv, onClick = { onConversationClick(conv.threadId.value) })
                    }
                }
            }
        }
    }
}

@Composable
fun SpamScreen(
    viewModel: SpamViewModel? = null,
    onConversationClick: (Long) -> Unit = {},
    onNotSpam: (Long) -> Unit = {},
) {
    // Live verdicts when a ViewModel is provided; fake seed for previews/tests.
    val live = viewModel?.conversations?.collectAsState()?.value
    val isLive = viewModel != null
    var fakeSpamList by androidx.compose.runtime.remember(viewModel) {
        mutableStateOf(
            if (isLive) emptyList()
            else listOf(
                Conversation(com.example.nospam.core.model.ThreadId(201), listOf(com.example.nospam.core.model.Participant("Win A Free Cruise!")), "Congratulations! You've been selected for an all-expenses-paid trip. Click here to claim.", System.currentTimeMillis(), 1, true, isSpam = true),
                Conversation(com.example.nospam.core.model.ThreadId(202), listOf(com.example.nospam.core.model.Participant("+1 (555) 928-1102")), "URGENT: Your account needs verification immediately or it will be suspended.", System.currentTimeMillis() - 86400000, 1, true, isSpam = true),
                Conversation(com.example.nospam.core.model.ThreadId(203), listOf(com.example.nospam.core.model.Participant("Crypto Alerts")), "Don't miss the next big pump! Join our exclusive VIP telegram group now.", System.currentTimeMillis() - 2 * 86400000, 1, true, isSpam = true),
            )
        )
    }
    // Session-dismissed ids for instant feedback; the persisted override also
    // removes the row via the live flow on the next emission.
    var dismissed by androidx.compose.runtime.remember(viewModel) { mutableStateOf(setOf<Long>()) }
    var showNotSpamSnack by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    fun markNotSpam(conv: Conversation) {
        if (isLive) dismissed = dismissed + conv.threadId.value
        else fakeSpamList = fakeSpamList.filterNot { it.threadId == conv.threadId }
        showNotSpamSnack = "${conv.participants.first().address} marked as not spam"
        onNotSpam(conv.threadId.value)
    }
    val spamList = (live ?: fakeSpamList).filterNot { it.threadId.value in dismissed }
    Column(modifier = Modifier.fillMaxSize()) {
        // Banner
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text("Spam messages will be deleted automatically after 30 days.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        // Bulk delete touches the system provider — offered only for the fake
        // seed. Live spam is cleared thread-by-thread via "Not spam".
        if (!isLive) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = { fakeSpamList = emptyList() }) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Empty Spam")
                }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(spamList, key = { it.threadId.value }) { conv ->
                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) {
                            markNotSpam(conv)
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
                            Text("Not spam", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = false
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).clickable { onConversationClick(conv.threadId.value) }.padding(horizontal = 12.dp, vertical = 8.dp)
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
                            androidx.compose.material3.TextButton(onClick = { markNotSpam(conv) }) { Text("Not spam") }
                        }
                    }
                }
            }
        }
        showNotSpamSnack?.let {
            androidx.compose.material3.Snackbar(modifier = Modifier.padding(16.dp)) { Text(it) }
        }
    }
}
// Previews for Phase 4.5
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Light EN")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "RTL FA", locale = "fa")
@Composable
fun ConversationsScreenPreview() {
    com.example.nospam.core.designsystem.theme.NoSpamTheme {
        ConversationsScreen()
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Archived Empty")
@Composable
fun ArchivedEmptyPreview() {
    com.example.nospam.core.designsystem.theme.NoSpamTheme {
        ArchivedScreen()
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Spam")
@Composable
fun SpamPreview() {
    com.example.nospam.core.designsystem.theme.NoSpamTheme {
        SpamScreen()
    }
}

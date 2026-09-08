package com.nospam.nospam.feature.thread

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.theme.MessageBubbleShapeIncoming
import com.nospam.nospam.core.designsystem.theme.MessageBubbleShapeOutgoing
import com.nospam.nospam.core.model.MessageType

@Composable
fun ThreadScreen(threadId: Long, address: String? = null, viewModel: ThreadViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(threadId, address) { viewModel.loadThread(threadId, address, context) }
    val uiState by viewModel.uiState.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages, key = { it.id.value }) { msg ->
                val isMe = msg.type == MessageType.SENT
                val isSuspected = msg.id.value in uiState.spamMessageIds && !isMe
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                    if (isSuspected) {
                        androidx.compose.material3.AssistChip(
                            onClick = { },
                            label = { Text("Suspected spam") },
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(if (isMe) MessageBubbleShapeOutgoing else MessageBubbleShapeIncoming)
                                .background(if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                msg.body,
                                color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    if (isSuspected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            androidx.compose.material3.TextButton(onClick = { uiState.onMarkNotSpam?.invoke(msg.id.value) }) { Text("Not spam") }
                            androidx.compose.material3.TextButton(onClick = { uiState.onReportSpam?.invoke(msg.id.value) }) { Text("Report spam") }
                        }
                    }
                }
            }
        }
        // Compose bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {}) { Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = {}) { Icon(Icons.Default.Face, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(
                value = uiState.draft,
                onValueChange = viewModel::onDraftChanged,
                placeholder = { Text(stringResource(R.string.compose_hint)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = viewModel::onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send_message_desc), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal data class Contact(val name: String, val detail: String, val phone: String)

// Placeholder until the Contacts provider is wired (needs READ_CONTACTS
// query in core:telephony + a repository). Mirrors the Stitch mock.
internal fun fakeContacts() = listOf(
    Contact("Alice Freeman", "Mobile • 555-0102", "5550102"),
    Contact("Amanda Jones", "Work • 555-0193", "5550193"),
    Contact("Ben Carter", "Home • 555-0144", "5550144"),
    Contact("Brian Smith", "Mobile • 555-0188", "5550188"),
    Contact("Catherine O'Neil", "Mobile • 555-0167", "5550167"),
    Contact("David Kim", "Work • 555-0112", "5550112"),
)

/**
 * Resolves a typed query to a destination address: an exact/contains match
 * on a known contact name wins, otherwise the raw query is treated as a
 * phone number/address. Pure logic so the IME-Done path is unit-testable.
 */
internal fun resolveRecipientAddress(query: String, contacts: List<Contact> = fakeContacts()): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    contacts.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.phone }
    contacts.firstOrNull { it.name.contains(trimmed, ignoreCase = true) }?.let { return it.phone }
    return trimmed
}

@Composable
fun NewConversationScreen(onAddressEntered: (String) -> Unit = {}) {
    // Hoisted + saveable: the field previously used value = "" with a no-op
    // onValueChange, so every keystroke was discarded and the IME ended up
    // writing to an inactive InputConnection.
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val contacts = androidx.compose.runtime.remember { fakeContacts() }
    val filtered = remember(query) {
        if (query.isBlank()) contacts
        else contacts.filter {
            it.name.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true)
        }
    }
    fun submit() {
        resolveRecipientAddress(query)?.let { onAddressEntered(it) }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.new_to), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.new_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { submit() },
            ),
        )
        Text(stringResource(R.string.new_top), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
        Row(
            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            contacts.take(5).forEach { contact ->
                Column(
                    modifier = Modifier.clickable { onAddressEntered(contact.phone) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            contact.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        contact.name.substringBefore(" "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.new_all), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.name }) { contact ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onAddressEntered(contact.phone) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            contact.name.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            contact.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// Previews
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread Light")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread RTL", locale = "fa")
@Composable
fun ThreadScreenPreview() {
    com.nospam.nospam.core.designsystem.theme.NoSpamTheme {
        ThreadScreen(threadId = 1)
    }
}

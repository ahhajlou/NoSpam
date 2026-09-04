package com.example.nospam.feature.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nospam.core.designsystem.theme.MessageBubbleShapeIncoming
import com.example.nospam.core.designsystem.theme.MessageBubbleShapeOutgoing
import com.example.nospam.core.model.MessageType

@Composable
fun ThreadScreen(threadId: Long, viewModel: ThreadViewModel = viewModel()) {
    LaunchedEffect(threadId) { viewModel.loadThread(threadId) }
    val uiState by viewModel.uiState.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages, key = { it.id.value }) { msg ->
                val isMe = msg.type == MessageType.SENT
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
                placeholder = { Text("SMS message") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = viewModel::onSend) {
                Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun NewConversationScreen(onThreadCreated: (Long) -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("To:", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = "", onValueChange = {}, placeholder = { Text("Type a name, phone number, or email") }, modifier = Modifier.fillMaxWidth())
        Text("Top contacts", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
        Text("All contacts — placeholder (Stitch new_conversation)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Previews
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread Light")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Thread RTL", locale = "fa")
@Composable
fun ThreadScreenPreview() {
    com.example.nospam.core.designsystem.theme.NoSpamTheme {
        ThreadScreen(threadId = 1)
    }
}

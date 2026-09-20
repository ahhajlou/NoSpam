// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.nospam.nospam.core.designsystem.component.Avatar
import com.nospam.nospam.core.designsystem.component.NoSpamTopAppBar
import com.nospam.nospam.core.designsystem.component.TopBarNavigation
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme

internal data class Contact(val name: String, val detail: String, val phone: String)

// Fallback seed for previews/tests when no provider is available.
internal fun fakeContacts() = listOf(
    Contact("Alice Freeman", "Mobile • 555-0102", "5550102"),
    Contact("Amanda Jones", "Work • 555-0193", "5550193"),
    Contact("Ben Carter", "Home • 555-0144", "5550144"),
    Contact("Brian Smith", "Mobile • 555-0188", "5550188"),
    Contact("Catherine O'Neil", "Mobile • 555-0167", "5550167"),
    Contact("David Kim", "Work • 555-0112", "5550112"),
)

private fun contactEntryToUi(e: com.nospam.nospam.core.model.ContactEntry) = Contact(
    name = e.displayName,
    detail = "${e.label ?: "Mobile"} • ${e.phone}",
    phone = e.phone
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    onNavigateUp: () -> Unit = {},
    onAddressEntered: (String) -> Unit = {},
    dataSource: com.nospam.nospam.core.telephony.TelephonyDataSource? = null,
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasContactPerm by remember {
        mutableStateOf(
            dataSource == null || androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasContactPerm = granted }
    var realContacts by remember { mutableStateOf<List<Contact>?>(null) }
    // Load real contacts when provider is available and permission granted
    androidx.compose.runtime.LaunchedEffect(dataSource, query, hasContactPerm) {
        if (dataSource == null || !hasContactPerm) {
            realContacts = null
            return@LaunchedEffect
        }
        val limit = 50
        val q = query.takeIf { it.isNotBlank() }
        realContacts = try {
            dataSource.getContacts(limit, q).map { contactEntryToUi(it) }
        } catch (_: Exception) { null }
    }
    val isPreview = dataSource == null
    val rc = realContacts
    val contacts: List<Contact> = when {
        isPreview -> remember { fakeContacts() }
        !hasContactPerm -> emptyList()
        rc != null -> rc
        else -> emptyList()
    }
    val filtered: List<Contact> = remember(query, contacts, rc, isPreview) {
        if (!isPreview && rc != null) contacts
        else if (query.isBlank()) contacts
        else contacts.filter {
            it.name.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true)
        }
    }
    fun submit() {
        resolveRecipientAddress(query)?.let { onAddressEntered(it) }
    }
    // Starting a conversation means typing a recipient, so the field takes focus
    // and the keyboard opens on arrival. Without it nothing is focused, and a
    // hardware Enter moves focus to the first focusable node — the up button —
    // and activates it.
    val recipientFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { recipientFocus.requestFocus() }
    Scaffold(
        topBar = {
            NoSpamTopAppBar(
                title = stringResource(R.string.new_conversation_title),
                navigation = TopBarNavigation.Back(onNavigateUp),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(stringResource(R.string.new_to), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.new_hint)) },
                modifier = Modifier.fillMaxWidth().focusRequester(recipientFocus),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { submit() },
                ),
            )
            if (!hasContactPerm && dataSource != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.contacts_permission_rationale),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { permLauncher.launch(android.Manifest.permission.READ_CONTACTS) }
                    ) { Text(stringResource(R.string.contacts_permission_allow)) }
                }
            }
            // A section header with nothing under it is worse than no section:
            // on a device with no contacts saved this screen showed "Top
            // contacts", a divider and "All contacts" over empty space.
            if (contacts.isNotEmpty()) {
                SectionHeader(stringResource(R.string.new_top))
                Row(
                    modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    contacts.take(5).forEach { contact ->
                    Column(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onAddressEntered(contact.phone) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .widthIn(max = 88.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                            Avatar(name = contact.name, colorKey = contact.phone, size = 56.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                contact.name.substringBefore(" "),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }
            if (filtered.isNotEmpty()) {
                SectionHeader(stringResource(R.string.new_all))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.phone }) { contact ->
                        ListItem(
                            headlineContent = { Text(contact.name) },
                            supportingContent = { Text(contact.detail) },
                            leadingContent = { Avatar(name = contact.name, colorKey = contact.phone) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { onAddressEntered(contact.phone) },
                        )
                    }
                }
            } else if (hasContactPerm || isPreview) {
                // Permission held and still nothing to show: say which of the two
                // it is, rather than leaving the recipient field over blank space.
                Text(
                    stringResource(
                        if (query.isBlank()) R.string.new_no_contacts else R.string.new_no_matches
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "New conversation")
@Composable
fun NewConversationScreenPreview() {
    NoSpamTheme { NewConversationScreen() }
}

/** Matches the inbox's section headers (`SectionHeader` in feature:conversations). */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

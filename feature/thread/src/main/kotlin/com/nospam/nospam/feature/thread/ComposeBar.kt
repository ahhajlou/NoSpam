package com.nospam.nospam.feature.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.nospam.nospam.core.telephony.TelephonyDataSource

/** Roughly a keyboard's height, so the panel swap does not move the compose bar. */
private val EMOJI_PANEL_HEIGHT = 280.dp

/**
 * Message input: emoji toggle, the field itself, a SIM picker on multi-SIM
 * devices, and send. Send is disabled while the draft is blank, and the emoji
 * panel takes the keyboard's place rather than stacking on top of it.
 */
@Composable
internal fun ComposeBar(
    draft: String,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    sims: List<TelephonyDataSource.SimInfo>,
    selectedSimId: Int?,
    onSimSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The field owns the cursor; the ViewModel owns the text. An emoji is
    // inserted at the cursor, not appended, and an external change (a forwarded
    // body) resets the cursor to the end.
    var field by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    LaunchedEffect(draft) {
        if (draft != field.text) field = TextFieldValue(draft, TextRange(draft.length))
    }
    var emojiOpen by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun update(value: TextFieldValue) {
        field = value
        onDraftChanged(value.text)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = {
                emojiOpen = !emojiOpen
                if (emojiOpen) keyboard?.hide() else keyboard?.show()
            }) {
                Icon(
                    if (emojiOpen) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                    contentDescription = stringResource(
                        if (emojiOpen) R.string.show_keyboard_desc else R.string.show_emoji_desc
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sims.size > 1) {
                SimPicker(sims, selectedSimId, onSimSelected)
            }
            TextField(
                value = field,
                onValueChange = ::update,
                placeholder = { Text(stringResource(R.string.compose_hint)) },
                modifier = Modifier.weight(1f),
                // SMS is often several lines; the bar grows to five and then scrolls.
                maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = onSend, enabled = field.text.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message_desc),
                    tint = if (field.text.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (emojiOpen) {
            EmojiPanel(onEmoji = { emoji -> update(field.insert(emoji)) })
        }
    }
}

/** Inserts [text] at the cursor, replacing any selection, and moves the cursor after it. */
internal fun TextFieldValue.insert(text: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val updated = this.text.replaceRange(start, end, text)
    return TextFieldValue(updated, TextRange(start + text.length))
}

/**
 * androidx's emoji picker: categories, search, skin tones, recently used, and
 * it tracks new Unicode releases. It is a View, so it comes in through
 * AndroidView.
 */
@Composable
private fun EmojiPanel(onEmoji: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply {
                    emojiGridColumns = 9
                    setOnEmojiPickedListener { onEmoji(it.emoji) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(EMOJI_PANEL_HEIGHT),
        )
    }
}

@Composable
private fun SimPicker(
    sims: List<TelephonyDataSource.SimInfo>,
    selectedSimId: Int?,
    onSimSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                sims.find { it.subscriptionId == selectedSimId }?.displayName
                    ?: stringResource(R.string.sim_label),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sims.forEach { sim ->
                DropdownMenuItem(
                    text = { Text(listOfNotNull(sim.displayName, sim.number).joinToString(" ")) },
                    onClick = {
                        onSimSelected(sim.subscriptionId)
                        expanded = false
                    },
                )
            }
        }
    }
}

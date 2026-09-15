package com.nospam.nospam.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One long-press action. [destructive] renders the label in the error color
 * (delete, report spam, block).
 */
data class ActionMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Long-press menu shared by inbox, archived, spam and thread-message rows.
 * Plain dialog list (no anchored DropdownMenu) so it works identically on
 * every surface and stays testable with straightforward text queries.
 */
@Composable
fun ActionMenuDialog(
    title: String,
    actions: List<ActionMenuItem>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                actions.forEach { action ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            action.onClick()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            action.label,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (action.destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

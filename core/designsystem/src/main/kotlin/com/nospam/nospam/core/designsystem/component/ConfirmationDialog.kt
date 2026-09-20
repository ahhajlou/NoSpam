// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nospam.nospam.core.designsystem.R

/**
 * Confirmation before an action that cannot be undone or reaches beyond the
 * app (hard delete from the SMS provider, a system-wide block). [text] should
 * say what will happen and how many items it affects; see TODO.md "Bulk spam
 * actions" for the rule bulk actions follow.
 *
 * [onConfirm] runs after the dialog asks to dismiss, so callers only clear
 * their "show dialog" state in [onDismiss].
 */
@Composable
fun ConfirmationDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    icon: ImageVector? = null,
    dismissLabel: String = stringResource(R.string.ds_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

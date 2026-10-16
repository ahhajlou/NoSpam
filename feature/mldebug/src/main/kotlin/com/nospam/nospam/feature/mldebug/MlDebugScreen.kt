// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.mldebug

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MlDebugScreen(
    viewModel: MlDebugViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.mldebug_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.mldebug_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.input,
            onValueChange = viewModel::onInputChanged,
            placeholder = { Text(stringResource(R.string.mldebug_hint)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )

        Button(
            onClick = viewModel::classify,
            enabled = !uiState.isClassifying && uiState.input.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isClassifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = stringResource(R.string.mldebug_running),
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else {
                Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.mldebug_run),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        when (uiState.error) {
            MlDebugError.CLASSIFIER_UNAVAILABLE -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.mldebug_error_unavailable),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            MlDebugError.FAILED -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.mldebug_error_failed),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            null -> Unit
        }

        uiState.result?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (result.isSpam) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = stringResource(
                                    if (result.isSpam) R.string.mldebug_label_spam
                                    else R.string.mldebug_label_ham
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (result.isSpam) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(
                                R.string.mldebug_score,
                                String.format(Locale.US, "%.4f", result.score)
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    TraceRow(
                        label = stringResource(R.string.mldebug_normalized),
                        value = result.normalized
                    )
                    Spacer(Modifier.height(8.dp))
                    TraceRow(
                        label = stringResource(R.string.mldebug_ngrams),
                        value = result.ngramCount.toString()
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "ML Debug Light")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "ML Debug Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "ML Debug RTL", locale = "fa")
@SuppressLint("ViewModelConstructorInComposable")
@Composable
private fun MlDebugScreenPreview() {
    NoSpamTheme {
        MlDebugScreen(viewModel = MlDebugViewModel())
    }
}
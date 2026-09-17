package com.nospam.nospam.feature.export

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.data.ExportRepository
import com.nospam.nospam.core.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ExportUiState(
    val isExporting: Boolean = false,
    val exportedCount: Int = 0,
    val error: String? = null,
    val successFile: String? = null,
    /** When on, each exported line carries a spam/ham label from on-device classification. */
    val includeLabels: Boolean = true,
    /** Random per-install id stamped on every exported line; never a device identifier. */
    val installId: String = "",
)

private val exportJson = Json { explicitNulls = true; encodeDefaults = true }

@Serializable
data class ExportRow(
    /**
     * Random per-install UUID, not a hardware id. Groups the lines of one
     * corpus without identifying the device it came from.
     */
    val installId: String,
    val timestamp: Long,
    val address: String,
    val text: String,
    val label: String? = null,
)

internal fun messageToJsonLine(
    installId: String,
    timestamp: Long,
    address: String,
    text: String,
    label: String? = null,
): String = exportJson.encodeToString(ExportRow(installId, timestamp, address, text, label))

internal fun writeJsonl(
    output: OutputStream,
    messages: List<Message>,
    installId: String,
) {
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (msg in messages) {
            writer.write(messageToJsonLine(installId, msg.date, msg.address, msg.body, null))
            writer.newLine()
        }
        writer.flush()
    }
}

internal suspend fun writeJsonlWithLabels(
    output: OutputStream,
    messages: List<Message>,
    installId: String,
    labelProvider: suspend (Message) -> String?,
) {
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (msg in messages) {
            val label = labelProvider(msg)
            writer.write(messageToJsonLine(installId, msg.date, msg.address, msg.body, label))
            writer.newLine()
        }
        writer.flush()
    }
}

/**
 * Debug-only corpus export. Reaches the message store and the stored verdicts
 * through [ExportRepository] rather than `core:telephony`/`core:database`, so
 * this feature goes through the repository layer like every other one.
 */
class ExportViewModel(
    private val repository: ExportRepository? = null,
    private val installIdProvider: suspend () -> String = { "" },
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    /**
     * Resolves the per-install id for display. Called from the screen rather
     * than `init` so constructing the ViewModel needs no main dispatcher.
     */
    suspend fun loadInstallId() {
        if (_uiState.value.installId.isNotEmpty()) return
        val id = withContext(Dispatchers.IO) { installIdProvider() }
        _uiState.value = _uiState.value.copy(installId = id)
    }

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(error = null, successFile = null)
    }

    fun onToggleIncludeLabels(include: Boolean) {
        _uiState.value = _uiState.value.copy(includeLabels = include)
    }

    fun export(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val installId = _uiState.value.installId.ifEmpty { installIdProvider() }
            try {
                _uiState.value = _uiState.value.copy(
                    isExporting = true,
                    exportedCount = 0,
                    error = null,
                    successFile = null,
                    installId = installId,
                )

                val messages = repository?.allMessages() ?: emptyList()
                if (messages.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            isExporting = false,
                            error = context.getString(R.string.export_empty),
                        )
                    }
                    return@launch
                }

                val includeLabels = _uiState.value.includeLabels
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    if (includeLabels && repository != null) {
                        writeJsonlWithLabels(output, messages, installId) { msg ->
                            repository.labelFor(msg)
                        }
                    } else {
                        writeJsonl(output, messages, installId)
                    }
                } ?: throw IllegalStateException("Cannot open output stream for $uri")

                val fileName = uri.lastPathSegment ?: uri.toString()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportedCount = messages.size,
                        successFile = fileName,
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        error = e.message ?: e.toString(),
                    )
                }
            }
        }
    }
}

package com.nospam.nospam.feature.export

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nospam.nospam.core.database.dao.MessageVerdictDao
import com.nospam.nospam.core.telephony.TelephonyDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ExportUiState(
    val isExporting: Boolean = false,
    val exportedCount: Int = 0,
    val error: String? = null,
    val successFile: String? = null,
    val includeLabels: Boolean = true,
)

private val exportJson = Json { explicitNulls = true; encodeDefaults = true }

@Serializable
data class ExportRow(
    val hwid: String,
    val date: Long,
    val address: String,
    val text: String,
    val label: String? = null,
)

internal fun messageToJsonLine(
    hwid: String,
    date: Long,
    address: String,
    text: String,
    label: String? = null,
): String = exportJson.encodeToString(ExportRow(hwid, date, address, text, label))

internal fun writeJsonl(
    output: OutputStream,
    messages: List<com.nospam.nospam.core.model.Message>,
    hwid: String,
    labelProvider: suspend (com.nospam.nospam.core.model.Message) -> String? = { null },
) {
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (msg in messages) {
            writer.write(messageToJsonLine(hwid, msg.date, msg.address, msg.body, null))
            writer.newLine()
        }
        writer.flush()
    }
}

internal suspend fun writeJsonlWithLabels(
    output: OutputStream,
    messages: List<com.nospam.nospam.core.model.Message>,
    hwid: String,
    labelProvider: suspend (com.nospam.nospam.core.model.Message) -> String?,
) {
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (msg in messages) {
            val label = labelProvider(msg)
            writer.write(messageToJsonLine(hwid, msg.date, msg.address, msg.body, label))
            writer.newLine()
        }
        writer.flush()
    }
}

internal suspend fun resolveLabel(
    message: com.nospam.nospam.core.model.Message,
    verdictDao: MessageVerdictDao?,
): String? {
    if (verdictDao == null) return null
    val entity = runCatching { verdictDao.getByMessageId(message.id.value) }.getOrNull() ?: return null
    val isSpam = entity.userLabel ?: entity.isSpam
    return if (isSpam) "spam" else "ham"
}

class ExportViewModel(
    private val telephony: TelephonyDataSource? = null,
    private val messageVerdictDao: MessageVerdictDao? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(error = null, successFile = null)
    }

    fun onToggleIncludeLabels(include: Boolean) {
        _uiState.value = _uiState.value.copy(includeLabels = include)
    }

    fun export(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = ExportUiState(isExporting = true)
                val hwid = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "unknown"

                val messages = telephony?.getAllMessages() ?: emptyList()
                if (messages.isEmpty()) {
                    // REMOVED withContext(Dispatchers.Main) - StateFlow is thread-safe!
                    _uiState.value = ExportUiState(error = context.getString(R.string.export_empty))
                    return@launch
                }

                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeJsonlWithLabels(output, messages, hwid) { msg ->
                        resolveLabel(msg, messageVerdictDao)
                    }
                } ?: throw IllegalStateException("Cannot open output stream for $uri")

                val fileName = uri.lastPathSegment ?: uri.toString()

                // REMOVED withContext(Dispatchers.Main) - StateFlow is thread-safe!
                _uiState.value = ExportUiState(
                    isExporting = false,
                    exportedCount = messages.size,
                    successFile = fileName
                )
            } catch (e: Exception) {
                // REMOVED withContext(Dispatchers.Main) - StateFlow is thread-safe!
                _uiState.value = ExportUiState(
                    isExporting = false,
                    error = e.message ?: e.toString()
                )
            }
        }
    }
}
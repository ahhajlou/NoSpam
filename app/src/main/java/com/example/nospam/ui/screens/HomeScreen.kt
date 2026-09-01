package com.example.nospam.ui.screens

import android.database.ContentObserver // <--- THIS WAS MISSING
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider // <--- Added for deprecation fix
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SmsItem(
    val sender: String,
    val body: String,
    val date: Long,
    val isRead: Boolean
)

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val smsList by rememberSmsInbox()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (smsList.isEmpty()) {
            item {
                Text(
                    text = "No messages found. Send a test SMS from the emulator!",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            items(smsList) { sms ->
                SmsListItem(sms)
            }
        }
    }
}

@Composable
fun rememberSmsInbox(): State<List<SmsItem>> {
    val context = LocalContext.current
    val smsList = remember { mutableStateOf(emptyList<SmsItem>()) }

    DisposableEffect(context) {
        // Load initial data
        smsList.value = getSmsInbox(context)

        // Create the observer
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                // Reload list when database changes
                smsList.value = getSmsInbox(context)
            }
        }

        // Register the observer
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer
        )

        // Unregister on dispose
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return smsList
}

@Composable
fun SmsListItem(sms: SmsItem) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(sms.date))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = sms.sender,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (sms.isRead) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.padding(4.dp))

            Text(
                text = sms.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    // Fixed deprecation warning
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

private fun getSmsInbox(context: Context): List<SmsItem> {
    val smsList = mutableListOf<SmsItem>()
    val uri = Telephony.Sms.Inbox.CONTENT_URI

    val projection = arrayOf(
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.READ
    )

    val sortOrder = "${Telephony.Sms.DATE} DESC"

    context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
        val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

        while (cursor.moveToNext()) {
            smsList.add(
                SmsItem(
                    sender = cursor.getString(addressIndex) ?: "Unknown",
                    body = cursor.getString(bodyIndex) ?: "",
                    date = cursor.getLong(dateIndex),
                    isRead = cursor.getInt(readIndex) == 1
                )
            )
        }
    }

    return smsList
}
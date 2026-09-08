package com.nospam.nospam

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.nospam.nospam.ui.NoSpamAppShell

// AppCompatActivity (not ComponentActivity): AppCompatDelegate applies
// per-app locales and recreates activities on pre-33 devices only for
// activities running through its delegate. Required for fa/RTL switching.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSendToIntent(intent)
        setContent {
            NoSpamAppShell()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendToIntent(intent)
    }

    private fun handleSendToIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SENDTO) return
        val address = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        // Normalize via PhoneNumberUtils (E.164 when possible, else raw) and log.
        // NavHost deep-link to NewConversation/Thread is wired via intent data.
        val normalized = try {
            android.telephony.PhoneNumberUtils.formatNumberToE164(
                address, java.util.Locale.getDefault().country
            ) ?: address.trim()
        } catch (_: Exception) { address.trim() }
        android.util.Log.d("MainActivity", "SENDTO for $normalized")
    }
}

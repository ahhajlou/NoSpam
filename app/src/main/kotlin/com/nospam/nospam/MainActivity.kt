// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nospam.nospam.navigation.LaunchTarget
import com.nospam.nospam.navigation.toLaunchTarget
import com.nospam.nospam.ui.NoSpamAppShell
import kotlinx.coroutines.flow.MutableStateFlow

// AppCompatActivity (not ComponentActivity): AppCompatDelegate applies
// per-app locales and recreates activities on pre-33 devices only for
// activities running through its delegate. Required for fa/RTL switching.
open class MainActivity : AppCompatActivity() {
    /** A conversation an intent asked for, until the navigation graph opens it. */
    private val launchTarget = MutableStateFlow<LaunchTarget?>(null)

    /**
     * Whether leaving the screen this activity started on finishes it, back to
     * the app that opened it, instead of going to the inbox.
     */
    protected open val leavesAtStart: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Suppress Samsung Typeface / AppLocalesStorageHelper disk read violations (Fix 4).
        val oldPolicy = android.os.StrictMode.allowThreadDiskReads()
        try {
            super.onCreate(savedInstanceState)
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
        enableEdgeToEdge()
        // Only a fresh launch reads its intent. A recreated activity (rotation,
        // process restore) gets the same intent back and must not open the
        // conversation a second time.
        if (savedInstanceState == null) launchTarget.value = intent?.toLaunchTarget()
        val app = application as NoSpamApplication
        setContent {
            val target by launchTarget.collectAsState()
            val dynamicColor by app.dynamicColor.collectAsState()
            val newSpamCount by app.container.newSpamCount.collectAsState(initial = 0)
            NoSpamAppShell(
                dynamicColor = dynamicColor,
                newSpamCount = newSpamCount,
                photoLoader = app.container.contactPhotos,
                launchTarget = target,
                onLaunchTargetHandled = { launchTarget.value = null },
                onLeaveStart = if (leavesAtStart) ::finish else null,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.toLaunchTarget()?.let { launchTarget.value = it }
    }
}

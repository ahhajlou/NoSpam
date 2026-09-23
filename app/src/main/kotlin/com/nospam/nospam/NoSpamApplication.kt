// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import android.app.Application
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.nospam.nospam.core.data.BackfillStatus
import com.nospam.nospam.core.model.ThemeSetting
import com.nospam.nospam.core.notifications.NotificationHelper
import com.nospam.nospam.ui.toNightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class NoSpamApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Wallpaper colors on or off, as stored; current from before the first activity. */
    lateinit var dynamicColor: StateFlow<Boolean>
        private set

    override fun onCreate() {
        Log.i("NoSpamPerf", "app started at ${android.os.SystemClock.elapsedRealtime()}ms")
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
            Log.i("NoSpamPerf", "StrictMode enabled for debug")
        }
        // Samsung Typeface.SetFlipFonts / SetAppTypeFace does disk I/O inside
        // Application.onCreate -> wrap the super call to suppress harmless OEM
        // StrictMode violations (Fix 4). Ignore if not needed.
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            super.onCreate()
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
        container = AppContainer(this)
        applyAppearance()
        NotificationHelper.createChannels(this)
        // Progress notification for the one-time history scan (silently no-ops
        // when notifications are denied on API 33+).
        appScope.launch {
            BackfillProgressNotifier(this@NoSpamApplication, container.spamBackfill, appScope).start()
        }
        // Warm classifier off main thread so first SMS doesn't pay 1.2 MB JSON load.
        appScope.launch {
            runCatching { container.classifier }
        }
        // Pre-warm database off main thread so lazy init does not block NavHost composition (Fix 2).
        appScope.launch {
            runCatching { container.database }
            // Country lookup binds the telephony service, which a trace measured
            // at ~68ms per call. Warming it here was measured as neutral on inbox
            // load (920-936ms vs 895-946ms without), so those calls are evidently
            // not on the critical path — kept only because it is free and should
            // help the very first launch, not as a proven win.
            runCatching {
                com.nospam.nospam.core.telephony.PhoneNumberNormalizer.warm(this@NoSpamApplication)
            }
        }
        // History backfill is one-shot: it runs after SMS permission is granted
        // (onboarding) and resumes once on a later cold start only if the process
        // died mid-scan. When nothing is pending we never touch SMS or the
        // classifier here, so an ordinary launch adds no scan overhead.
        appScope.launch {
            if (container.settingsRepository.isBackfillPending()) {
                container.spamBackfill.ensureStarted()
            }
        }
        // Any completed scan (the resumed one or a Settings re-check) clears the
        // pending flag, so the resume never fires a second time.
        appScope.launch {
            container.spamBackfill.status
                .dropWhile { it is BackfillStatus.Idle }
                .first { it is BackfillStatus.Done || it is BackfillStatus.Cancelled || it is BackfillStatus.Failed }
            container.settingsRepository.setBackfillPending(false)
        }
    }

    /**
     * Puts the user's theme in place before the first activity is created.
     * Applied any later, AppCompat recreates the activity, and someone who chose
     * Light or Dark would see the system theme flash on every cold start. So the
     * first value is read here, blocking: one small DataStore file, read on the
     * IO dispatcher and capped at [APPEARANCE_READ_TIMEOUT_MS]. That read was
     * measured at 21-38ms on a debug emulator, so it only happens when the user
     * has changed an appearance setting; otherwise the defaults are known
     * without reading. Changes after that apply as they are saved.
     */
    private fun applyAppearance() {
        val settings = container.settingsRepository
        val started = SystemClock.elapsedRealtime()
        val defaults = ThemeSetting.SYSTEM to false
        val (theme, dynamic) = if (!settings.hasAppearanceSettings()) defaults else runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(APPEARANCE_READ_TIMEOUT_MS) {
                settings.theme.first() to settings.dynamicColor.first()
            }
        } ?: defaults
        Log.i("NoSpamPerf", "appearance read in ${SystemClock.elapsedRealtime() - started}ms")
        AppCompatDelegate.setDefaultNightMode(theme.toNightMode())
        dynamicColor = settings.dynamicColor.stateIn(appScope, SharingStarted.Eagerly, dynamic)
        appScope.launch {
            settings.theme.collect {
                withContext(Dispatchers.Main) { AppCompatDelegate.setDefaultNightMode(it.toNightMode()) }
            }
        }
    }

    private companion object {
        const val APPEARANCE_READ_TIMEOUT_MS = 500L
    }
}

package com.nospam.nospam

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.nospam.nospam.core.data.BackfillStatus
import com.nospam.nospam.core.notifications.NotificationHelper
import com.nospam.nospam.feature.settings.SpamPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NoSpamApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        NotificationHelper.createChannels(this)
        // Progress notification for the one-time history scan (silently no-ops
        // when notifications are denied on API 33+).
        appScope.launch {
            BackfillProgressNotifier(this@NoSpamApplication, container.spamBackfill, appScope).start()
        }
        // Warm classifier off main thread so first SMS doesn't pay 1.2 MB JSON load.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.classifier }
        }
        // Pre-warm database off main thread so lazy init does not block NavHost composition (Fix 2).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.database }
        }
        // History backfill is one-shot: it runs after SMS permission is granted
        // (onboarding) and resumes once on a later cold start only if the process
        // died mid-scan. When nothing is pending we never touch SMS or the
        // classifier here, so an ordinary launch adds no scan overhead.
        appScope.launch {
            if (SpamPreferences.isBackfillPending(this@NoSpamApplication)) {
                container.spamBackfill.ensureStarted()
            }
        }
        // Any completed scan (the resumed one or a Settings re-check) clears the
        // pending flag, so the resume never fires a second time.
        appScope.launch {
            container.spamBackfill.status
                .dropWhile { it is BackfillStatus.Idle }
                .first { it is BackfillStatus.Done || it is BackfillStatus.Cancelled || it is BackfillStatus.Failed }
            SpamPreferences.setBackfillPending(this@NoSpamApplication, false)
        }
    }
}

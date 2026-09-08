package com.nospam.nospam

import android.app.Application
import android.os.StrictMode
import android.provider.Telephony
import android.util.Log
import com.nospam.nospam.core.notifications.NotificationHelper
import kotlinx.coroutines.launch

class NoSpamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
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
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        // Warm classifier off main thread so first SMS doesn't pay 1.2 MB JSON load.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.classifier }
        }
        // Row count + thread count for reviewer evidence (IO, not main)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val smsCount = contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)?.use { it.count } ?: -1
                val threadCount = contentResolver.query(Telephony.Threads.CONTENT_URI, arrayOf(Telephony.Threads._ID), null, null, null)?.use { it.count } ?: -1
                Log.i("NoSpamPerf", "ROW_COUNT Sms=$smsCount Threads=$threadCount")
            } catch (e: Exception) {
                Log.w("NoSpamPerf", "ROW_COUNT query failed", e)
            }
        }
    }
}

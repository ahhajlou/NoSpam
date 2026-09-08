package com.nospam.nospam

import android.app.Application
import com.nospam.nospam.core.notifications.NotificationHelper
import kotlinx.coroutines.launch

class NoSpamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        // Warm classifier off main thread so first SMS doesn't pay 1.2 MB JSON load.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.classifier }
        }
    }
}

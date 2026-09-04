package com.example.nospam

import android.app.Application
import com.example.nospam.core.notifications.NotificationHelper

class NoSpamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
    }
}

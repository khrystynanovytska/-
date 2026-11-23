package com.example

import android.app.Application
import com.example.notifications.OutageNotificationHelper

class SvitloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OutageNotificationHelper.ensureChannel(this)
    }
}

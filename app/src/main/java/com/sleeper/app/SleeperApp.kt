package com.sleeper.app

import android.app.Application
import com.sleeper.app.utils.DevLog

class SleeperApp : Application() {

    companion object {
        private const val TAG = "SleeperApp"
    }

    override fun onCreate() {
        super.onCreate()
        DevLog.d(TAG, "Sleeper application started")
    }
}

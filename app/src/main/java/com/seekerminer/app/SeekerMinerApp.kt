package com.seekerminer.app

import android.app.Application
import com.seekerminer.app.utils.DevLog

class SeekerMinerApp : Application() {
    
    companion object {
        private const val TAG = "SeekerMinerApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        DevLog.d(TAG, "SeekerMiner application started")
    }
}

package com.largebatata.fcmhelper

import android.app.Application
import com.largebatata.fcmhelper.wake.EnhancedWakeManager

class HelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReaderDiagnostics(this).record("APP_PROCESS_START")
        EventHistoryRepository.initialize(this)
        EnhancedWakeManager.initialize(this)
    }
}

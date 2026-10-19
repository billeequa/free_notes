package com.plainnotes.android

import android.app.Application
import androidx.work.Configuration

/** WorkManager is constructed only by enabled sync; no startup initializer. */
class JNotesApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        com.plainnotes.android.sync.SyncHealth.onProcessStart(this)
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            // These failures happen on WorkManager threads, outside callers' try/catch.
            .setInitializationExceptionHandler { com.plainnotes.android.sync.SyncHealth.pause(this) }
            .setSchedulingExceptionHandler { com.plainnotes.android.sync.SyncHealth.pause(this) }
            .build()
}

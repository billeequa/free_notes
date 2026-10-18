package com.plainnotes.android

import android.app.Application
import androidx.work.Configuration

/** WorkManager is constructed only by enabled sync; no startup initializer. */
class JNotesApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
}

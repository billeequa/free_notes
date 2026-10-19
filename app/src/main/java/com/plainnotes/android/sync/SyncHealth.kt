package com.plainnotes.android.sync

import android.content.Context

/** Scheduler health is separate from consent and from the durable edit queue. */
object SyncHealth {
    private const val PAUSED="scheduler_paused"
    private const val STARTING="scheduler_starting"
    const val MESSAGE="Saved on phone — background sync is paused. Open Settings → Web Sync to retry."
    fun paused(c: Context)=SyncRuntime.prefs(c).getBoolean(PAUSED,false)
    fun onProcessStart(c: Context) {
        // A process that died while starting WorkManager must not enter a boot loop.
        if(SyncRuntime.prefs(c).getBoolean(STARTING,false))pause(c)
    }
    fun starting(c: Context) {SyncRuntime.prefs(c).edit().putBoolean(STARTING,true).commit()}
    fun started(c: Context) {SyncRuntime.prefs(c).edit().putBoolean(STARTING,false).commit()}
    fun pause(c: Context) {
        SyncRuntime.prefs(c).edit().putBoolean(PAUSED,true).putBoolean(STARTING,false).putString("status",MESSAGE).commit()
    }
    fun retry(c: Context) {SyncRuntime.prefs(c).edit().putBoolean(PAUSED,false).putBoolean(STARTING,false).commit()}
}

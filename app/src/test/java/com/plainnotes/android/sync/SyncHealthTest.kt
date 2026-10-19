package com.plainnotes.android.sync

import com.plainnotes.android.JNotesApplication
import com.plainnotes.android.data.TodoItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncHealthTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        SyncRuntime.prefs(context).edit().clear().commit()
        val db=SyncRuntime.store(context).writableDatabase
        for(table in listOf("outbox","conflicts","records","meta"))db.execSQL("DELETE FROM $table")
    }
    @Test fun unfinishedSchedulerStartupIsPausedOnNextProcessWithoutLosingConsentOrPendingEdits() {
        val s=SyncRuntime.store(context);val task=TodoItem(title="Local edit survives")
        s.idFor("task:${task.id}");s.capture("task:${task.id}",SyncPayload.task(task),false);s.setMeta("key_id","original")
        val pending=s.pending()
        SyncRuntime.prefs(context).edit().putBoolean("enabled",true).commit()
        SyncHealth.starting(context);SyncHealth.onProcessStart(context)
        assertTrue(SyncHealth.paused(context));assertTrue(SyncRuntime.enabled(context))
        SyncRuntime.schedule(context,periodic=true)
        assertEquals(pending,s.pending());assertEquals("original",s.meta("key_id"))
        assertEquals(SyncHealth.MESSAGE,SyncRuntime.prefs(context).getString("status",null))
    }
    @Test fun asynchronousWorkManagerFailuresPauseOnlyBackgroundSync() {
        val configuration=(context as JNotesApplication).workManagerConfiguration
        assertNotNull(configuration.initializationExceptionHandler)
        assertNotNull(configuration.schedulingExceptionHandler)
        SyncRuntime.prefs(context).edit().putBoolean("enabled",true).commit()
        configuration.initializationExceptionHandler!!.accept(IllegalStateException("synthetic unavailable work database"))
        assertTrue(SyncHealth.paused(context));assertTrue(SyncRuntime.enabled(context))
        SyncHealth.retry(context);assertFalse(SyncHealth.paused(context))
        configuration.schedulingExceptionHandler!!.accept(IllegalStateException("synthetic unavailable scheduler"))
        assertTrue(SyncHealth.paused(context));assertTrue(SyncRuntime.enabled(context))
    }
    @Test fun successfulStartupDoesNotPauseOnNextLaunch() {
        SyncHealth.starting(context);SyncHealth.started(context);SyncHealth.onProcessStart(context)
        assertFalse(SyncHealth.paused(context))
    }
}

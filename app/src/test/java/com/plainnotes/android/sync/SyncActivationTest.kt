package com.plainnotes.android.sync

import com.plainnotes.android.data.TodoItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncActivationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        SyncRuntime.prefs(context).edit().clear().commit()
        val db=SyncRuntime.store(context).writableDatabase
        for(table in listOf("outbox","conflicts","records","meta"))db.execSQL("DELETE FROM $table")
    }
    @Test fun schedulingFailureLeavesSetupRetryableAndPreservesPendingEditsAndIdentity() {
        val store=SyncRuntime.store(context)
        val task=TodoItem(title="Keep pending edits")
        store.idFor("task:${task.id}");store.capture("task:${task.id}",SyncPayload.task(task),false)
        store.setMeta("owner","owner");store.setMeta("key_id","same-key");store.setMeta("epoch","same-epoch")
        val pending=store.pending()
        SyncRuntime.prefs(context).edit().putString("backup","verified-backup.zip").commit()
        val failure=assertThrows(IOException::class.java) {
            SyncRuntime.activate(context) {
                assertTrue(SyncRuntime.enabled(context))
                throw IllegalStateException("synthetic scheduling failure")
            }
        }
        assertFalse(SyncRuntime.enabled(context));assertTrue(failure.message!!.contains("Try enabling sync again"))
        assertEquals(pending,store.pending());assertEquals("owner",store.meta("owner"));assertEquals("same-key",store.meta("key_id"));assertEquals("same-epoch",store.meta("epoch"))
        assertEquals("verified-backup.zip",SyncRuntime.prefs(context).getString("backup",null))
        SyncRuntime.activate(context) {assertTrue(SyncRuntime.enabled(context))}
        assertTrue(SyncRuntime.enabled(context));assertEquals(pending,store.pending())
    }
    @Test fun activationSchedulesOnlyAfterEnablingAndReportsPendingUpload() {
        var called=false
        SyncRuntime.activate(context) {called=true;assertTrue(SyncRuntime.enabled(context))}
        assertTrue(called);assertTrue(SyncRuntime.enabled(context))
        assertEquals("Saved on phone — pending initial upload",SyncRuntime.prefs(context).getString("status",null))
    }
}

package com.plainnotes.android.sync

import com.plainnotes.android.data.TodoItem
import android.net.Uri
import com.plainnotes.android.model.EditableNote
import java.time.OffsetDateTime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store:SyncStore
    private val date=OffsetDateTime.parse("2026-10-18T12:00:00Z")
    @Before fun setup(){context.deleteDatabase("web-sync.db");SyncRuntime.prefs(context).edit().clear().commit();store=SyncStore(context)}
    @After fun close(){store.close()}
    @Test fun disabledCaptureDoesNotCreateDatabaseOrScheduleWork() {
        val n=EditableNote(Uri.parse("content://test/n"),"n.txt","Untouched","body",date,date,false)
        SyncRuntime.captureNote(context,n);SyncRuntime.captureTodos(context,listOf(TodoItem(title="local")))
        SyncRuntime.schedule(context,periodic=true)
        assertFalse(context.getDatabasePath("web-sync.db").exists())
        assertFalse(SyncRuntime.enabled(context))
    }
    @Test fun crashedRetryRetainsOperationAndLaterEditIsQueuedAfterAcknowledgment() {
        val item=TodoItem(title="before",addedAt=date);val local="task:${item.id}";store.idFor(local)
        store.capture(local,SyncPayload.task(item),false)
        val first=store.pending().single();val envelope=JSONObject().put("opaque","same nonce")
        store.prepare(first.op,envelope)
        store.capture(local,SyncPayload.task(item.copy(title="edit while sending")),false)
        assertEquals(first.op,store.pending().single().op)
        assertEquals(envelope.toString(),store.pending().single().envelope)
        store.close();store=SyncStore(context)
        assertEquals(first.op,store.pending().single().op)
        store.acknowledge(first,1,5)
        val second=store.pending().single();assertNotEquals(first.op,second.op);assertEquals(1,second.expected)
        assertTrue(second.content.contains("edit while sending"));assertEquals("5",store.meta("floor"))
        store.acknowledge(second,2,6);assertTrue(store.pending().isEmpty())
    }
    @Test fun uriRenameRetainsStableUuidAndDoesNotAddDuplicate() {
        val first="note:content://test/old";val next="note:content://test/new";val id=store.idFor(first)
        store.move(first,next);assertEquals(id,store.idFor(next));assertEquals(1,store.records().size)
    }
    @Test fun remotePendingDeletionAndReopenRemainRevisionedAndConflictsBlockUploads() {
        val item=TodoItem(title="task",addedAt=date);val local="task:${item.id}";store.idFor(local)
        val p=SyncPayload.task(item);store.capture(local,p,false)
        store.acknowledge(store.pending().single(),1,1)
        store.capture(local,p,true);assertTrue(store.pending().single().deleted)
        val r=JSONObject().put("id",item.id).put("revision",2).put("seq",2).put("deleted",false)
        store.conflict(item.id,p,true,r,p);assertTrue(store.pending().isEmpty());assertEquals(1,store.conflicts().size)
        store.accept(item.id,local,r,p,p,true);store.clearConflict(item.id)
        assertEquals(2,store.pending().single().expected)
        store.capture(local,p,false)
        store.acknowledge(store.pending().single(),3,3)
        assertFalse(store.pending().single().deleted);assertEquals(3,store.pending().single().expected)
    }
    @Test fun taskMergePreservesDisjointCompletionAndFlagChangesAndRejectsSameField() {
        val item=TodoItem(title="initial",addedAt=date);val b=SyncPayload.task(item)
        val local=SyncPayload.task(item.copy(title="phone"));val remote=SyncPayload.task(item.copy(completedAt=date.plusHours(1)))
        val merged=SyncPayload.mergeTask(b,local,remote)!!;assertEquals("phone",merged.getString("title"));assertFalse(merged.isNull("completed_at"))
        assertNull(SyncPayload.mergeTask(b,local,remote.put("title","web")))
    }
}

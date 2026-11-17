package com.plainnotes.android.sync

import com.plainnotes.android.data.TodoItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RepeatingSyncTest {
    @Test fun repeatingMetadataSurvivesSyncAndLegacyPayloadsStayUnchanged() {
        val item = TodoItem(title = "Read", repeatOnComplete = true)
        val payload = SyncPayload.task(item)
        assertTrue(SyncPayload.todo(SyncPayload.validate(payload, item.id)).repeatOnComplete)
        val legacy = SyncPayload.task(item.copy(repeatOnComplete = false))
        assertFalse(legacy.has("repeat_on_complete"))
        assertFalse(SyncPayload.todo(legacy).repeatOnComplete)
        val explicitFalse = JSONObject(legacy.toString()).put("repeat_on_complete", false)
        assertEquals(SyncPayload.canonical(legacy), SyncPayload.canonical(SyncPayload.validate(explicitFalse, item.id)))
        assertThrows(IllegalArgumentException::class.java) {
            SyncPayload.validate(JSONObject(payload.toString()).put("repeat_on_complete", "true"), item.id)
        }
    }

    @Test fun stoppingRepeatMergesWithAnIndependentDescriptionEdit() {
        val base = SyncPayload.task(TodoItem(title = "Read", repeatOnComplete = true))
        val stopped = JSONObject(base.toString()).apply { remove("repeat_on_complete") }
        val edited = JSONObject(base.toString()).put("description", "New detail")
        val merged = SyncPayload.mergeTask(base, stopped, edited)!!
        assertFalse(merged.has("repeat_on_complete"))
        assertEquals("New detail", merged.getString("description"))
    }
}

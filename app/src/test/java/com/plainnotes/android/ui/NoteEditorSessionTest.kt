package com.plainnotes.android.ui

import android.net.Uri
import com.plainnotes.android.model.EditableNote
import java.time.OffsetDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorSessionTest {
    private fun note() = EditableNote(mock(Uri::class.java), "stable.txt", "Title", "Original",
        OffsetDateTime.now(), OffsetDateTime.now(), false)

    @Test fun typingDuringSaveDoesNotCancelWriteOrOverwriteNewText() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val session = NoteEditorSession(note(), backgroundScope) {
            writes += it.body
            if (writes.size == 1) gate.await()
            it
        }
        session.edit(body = "First")
        advanceTimeBy(601); runCurrent()
        assertEquals(listOf("First"), writes)
        session.edit(body = "Second") // Cancels only the timer, never its started write.
        gate.complete(Unit); runCurrent()
        assertEquals("Second", session.note.value.body)
        assertEquals(listOf("First", "Second"), writes)
        assertEquals("Saved", session.status.value)
    }

    @Test fun failedWriteKeepsDraftAndRetrySavesIt() = runTest {
        var fail = true
        val session = NoteEditorSession(note(), backgroundScope) { if (fail) null else it }
        session.edit(body = "Keep this")
        assertFalse(session.save())
        assertEquals("Keep this", session.note.value.body)
        assertTrue(session.status.value.startsWith("Save failed"))
        fail = false
        assertTrue(session.save())
        assertEquals("Saved", session.status.value)
    }

    @Test fun cancellingCallerDoesNotCancelSave() = runTest {
        val gate = CompletableDeferred<Unit>()
        var written = false
        val session = NoteEditorSession(note(), backgroundScope) { gate.await(); written = true; it }
        session.edit(body = "Keep this")
        val caller = async { session.save() }
        runCurrent(); caller.cancel(); gate.complete(Unit); runCurrent()
        assertTrue(written)
    }

    @Test fun recoveredDraftIsWrittenEvenWithoutAnotherEdit() = runTest {
        var writes = 0
        val session = NoteEditorSession(note(), backgroundScope, recovered = true) { writes++; it }
        assertTrue(session.save())
        assertEquals(1, writes)
    }
}

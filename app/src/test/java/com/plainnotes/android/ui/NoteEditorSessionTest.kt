package com.plainnotes.android.ui

import android.net.Uri
import com.plainnotes.android.model.EditableNote
import java.time.OffsetDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class NoteEditorSessionTest {
    private fun note() = EditableNote(mock(Uri::class.java), "note.txt", "Title", "Original",
        OffsetDateTime.now(), OffsetDateTime.now(), false)

    @Test fun saveCannotOverwriteNewerTyping() = runBlocking {
        val session = NoteEditorSession(note())
        session.body.value = "First edit"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saving = async {
            session.save { snapshot -> entered.complete(Unit); release.await(); snapshot }
        }
        entered.await()
        session.body.value = "Second edit"
        release.complete(Unit)
        assertFalse(saving.await())
        assertEquals("Second edit", session.body.value)
        assertEquals("First edit", session.savedNote.body)
        assertTrue(session.isDirty)
        assertTrue(session.save { it })
        assertEquals("Second edit", session.savedNote.body)
    }

    @Test fun failedSaveRetainsDraftAndDirtyState() = runBlocking {
        val session = NoteEditorSession(note())
        session.body.value = "Keep me"
        assertFalse(session.save { null })
        assertEquals("Keep me", session.body.value)
        assertTrue(session.isDirty)
        assertFalse(session.isSaving)
    }

    @Test fun serializedSavesUseNewProviderUri() = runBlocking {
        val session = NoteEditorSession(note())
        val renamedUri = mock(Uri::class.java)
        session.title.value = "Renamed"
        assertTrue(session.save { it.copy(documentUri = renamedUri, filename = "renamed.txt") })
        session.body.value = "Next edit"
        assertTrue(session.save {
            assertSame(renamedUri, it.documentUri)
            assertEquals("renamed.txt", it.filename)
            it
        })
    }
}

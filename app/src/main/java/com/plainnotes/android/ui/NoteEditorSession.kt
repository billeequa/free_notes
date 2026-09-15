package com.plainnotes.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.plainnotes.android.model.EditableNote
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retained by the ViewModel across rotation and provider URI changes. */
class NoteEditorSession(val initialNote: EditableNote) {
    val title = mutableStateOf(initialNote.title)
    val body = mutableStateOf(initialNote.body)
    var savedNote by mutableStateOf(initialNote)
        private set
    var isSaving by mutableStateOf(false)
        private set
    private val mutex = Mutex()

    val isDirty: Boolean get() = title.value != savedNote.title || body.value != savedNote.body

    suspend fun save(saveNote: suspend (EditableNote) -> EditableNote?): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            if (!isDirty) return@withLock true
            isSaving = true
            try {
                val snapshot = savedNote.copy(title = title.value, body = body.value)
                val saved = saveNote(snapshot) ?: return@withLock false
                // Acknowledging a snapshot must never overwrite edits made while it was in flight.
                savedNote = saved
                !isDirty
            } finally {
                isSaving = false
            }
        }
    }
}

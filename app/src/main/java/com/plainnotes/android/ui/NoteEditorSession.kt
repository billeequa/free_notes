package com.plainnotes.android.ui

import com.plainnotes.android.model.EditableNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owned by the ViewModel: leaving composition or rotating cannot cancel a write. */
class NoteEditorSession(
    initial: EditableNote,
    private val scope: CoroutineScope,
    private val recovered: Boolean = false,
    private val write: suspend (EditableNote) -> EditableNote?,
) {
    private val _note = MutableStateFlow(initial)
    val note = _note.asStateFlow()
    private val _status = MutableStateFlow(if (recovered) "Recovered draft · tap to save" else "Saved")
    val status = _status.asStateFlow()
    private var persisted: EditableNote? = if (recovered) null else initial
    private var debounce: Job? = null
    private val mutex = Mutex()

    fun edit(title: String = _note.value.title, body: String = _note.value.body) {
        if (title == _note.value.title && body == _note.value.body) return
        _note.value = _note.value.copy(title = title, body = body)
        _status.value = "Unsaved changes"
        debounce?.cancel()
        debounce = scope.launch {
            delay(600)
            // The actual write is a sibling job, never a child of the debounce timer.
            save()
        }
    }

    suspend fun save(): Boolean = scope.async {
        mutex.withLock {
            while (_note.value.title != persisted?.title || _note.value.body != persisted?.body) {
                val snapshot = _note.value
                _status.value = "Saving…"
                val saved = write(snapshot)
                if (saved == null) {
                    _status.value = "Save failed · tap to retry"
                    return@withLock false
                }
                persisted = saved
                // Acknowledging a snapshot must never replace newer keystrokes.
                _note.value = _note.value.copy(
                    documentUri = saved.documentUri,
                    filename = saved.filename,
                    modifiedAt = saved.modifiedAt,
                )
            }
            _status.value = "Saved"
            true
        }
    }.await()

    fun saveInBackground() {
        scope.launch { save() }
    }
}

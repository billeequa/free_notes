package com.plainnotes.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plainnotes.android.data.NotesRepository
import com.plainnotes.android.model.EditableNote
import com.plainnotes.android.model.NoteDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ThemeMode(val storageValue: String) {
    LIGHT("light"),
    LIGHT_2("light_2"),
    LIGHT_3("light_3"),
    DARK_1("dark_1"),
    DARK_2("dark_2"),
    DARK_3("dark_3");

    companion object {
        fun fromStorage(value: String): ThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: DARK_1
        }
    }
}

enum class NoteSortMode(val storageValue: String) {
    MODIFIED("modified"),
    CREATED("created");

    companion object {
        fun fromStorage(value: String): NoteSortMode {
            return entries.firstOrNull { it.storageValue == value } ?: MODIFIED
        }
    }
}

data class PlainNotesUiState(
    val hasLoadedStorageConfig: Boolean = false,
    val isStorageConfigured: Boolean = false,
    val selectedFolderName: String? = null,
    val themeMode: ThemeMode = ThemeMode.DARK_1,
    val fontScale: Float = 1.0f,
    val noteSortMode: NoteSortMode = NoteSortMode.MODIFIED,
    val isLoading: Boolean = true,
    val notes: List<NoteDocument> = emptyList(),
    val trash: List<NoteDocument> = emptyList(),
    val statusMessage: String? = null,
)

class PlainNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotesRepository(application)
    val todos = TodoSession(viewModelScope, repository::loadTodos, repository::saveTodos)
    private val _uiState = MutableStateFlow(PlainNotesUiState())
    val uiState: StateFlow<PlainNotesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.rootFolderUri().collectLatest { uri ->
                if (uri == null) {
                    _uiState.value = PlainNotesUiState(
                        hasLoadedStorageConfig = true,
                        isStorageConfigured = false,
                        themeMode = _uiState.value.themeMode,
                        fontScale = _uiState.value.fontScale,
                        noteSortMode = _uiState.value.noteSortMode,
                        isLoading = false,
                    )
                } else {
                    refreshState()
                }
            }
        }

        viewModelScope.launch {
            repository.themeMode().collectLatest { mode ->
                _uiState.update { state -> state.copy(themeMode = ThemeMode.fromStorage(mode)) }
            }
        }

        viewModelScope.launch {
            repository.fontScale().collectLatest { scale ->
                _uiState.update { state -> state.copy(fontScale = scale) }
            }
        }

        viewModelScope.launch {
            repository.noteSortMode().collectLatest { sortMode ->
                val parsed = NoteSortMode.fromStorage(sortMode)
                _uiState.update { state -> state.copy(noteSortMode = parsed) }
                if (_uiState.value.isStorageConfigured) {
                    refreshState()
                }
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                if (!todos.flush()) throw IllegalStateException("Save the to-do list before changing folders.")
                repository.persistRootFolder(uri)
                todos.reset()
                refreshState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to use that folder.")
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                refreshState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to refresh notes.")
            }
        }
    }

    suspend fun createNote(): EditableNote? {
        return try {
            val created = repository.createBlankNote()
            refreshState()
            created
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            postStatus(error.message ?: "Unable to create a new note.")
            null
        }
    }

    suspend fun loadNote(uriString: String): EditableNote? {
        return try {
            repository.loadEditableNote(uriString)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            postStatus(error.message ?: "Unable to open that note.")
            null
        }
    }

    private var editorSession: NoteEditorSession? = null

    suspend fun openEditor(uriString: String): NoteEditorSession? {
        editorSession?.takeIf { it.note.value.documentUri.toString() == uriString }?.let { return it }
        val note = loadNote(uriString) ?: return null
        return NoteEditorSession(note, viewModelScope, repository.hasPendingDraft(note.documentUri), ::saveNote).also { editorSession = it }
    }

    fun closeEditor() {
        editorSession = null
        refresh()
    }

    suspend fun saveNote(note: EditableNote): EditableNote? {
        return try {
            repository.saveNote(note)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            postStatus(error.message ?: "Unable to save the note.")
            null
        }
    }

    fun moveToTrash(uriString: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                repository.moveToTrash(uriString)
                editorSession = null
                refreshState()
                onDone?.invoke()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to move the note to trash.")
            }
        }
    }

    fun restoreFromTrash(uriString: String) {
        viewModelScope.launch {
            try {
                repository.restoreFromTrash(uriString)
                refreshState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to restore the note.")
            }
        }
    }

    fun deletePermanently(uriString: String) {
        viewModelScope.launch {
            try {
                repository.deletePermanently(uriString)
                refreshState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to permanently delete the note.")
            }
        }
    }

    fun exportNotes() {
        viewModelScope.launch {
            try {
                val result = repository.exportActiveNotes()
                postStatus("Exported notes to ${result.fileName}.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to export notes.")
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { state -> state.copy(statusMessage = null) }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            try {
                repository.setThemeMode(themeMode.storageValue)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to update the theme setting.")
            }
        }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch {
            try {
                repository.setFontScale(scale)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to update the font size.")
            }
        }
    }

    fun setNoteSortMode(sortMode: NoteSortMode) {
        viewModelScope.launch {
            try {
                repository.setNoteSortMode(sortMode.storageValue)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to update note sorting.")
            }
        }
    }

    fun renameNote(uriString: String, newTitle: String) {
        viewModelScope.launch {
            try {
                repository.renameNote(uriString, newTitle)
                refreshState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to rename the note.")
            }
        }
    }

    private suspend fun refreshState() {
        _uiState.update { it.copy(isLoading = true) }
        val folderInfo = repository.getFolderInfo()
        val notes = sortNotes(repository.listActiveNotes(), _uiState.value.noteSortMode)
        val trash = repository.listTrashedNotes()
        _uiState.update { state ->
            state.copy(
                hasLoadedStorageConfig = true,
                isStorageConfigured = folderInfo != null,
                selectedFolderName = folderInfo?.displayName,
                isLoading = false,
                notes = notes,
                trash = trash,
            )
        }
    }

    private fun postStatus(message: String) {
        _uiState.update { state -> state.copy(statusMessage = message) }
    }

    private fun sortNotes(notes: List<NoteDocument>, sortMode: NoteSortMode): List<NoteDocument> {
        return when (sortMode) {
            NoteSortMode.MODIFIED -> notes.sortedByDescending { it.modifiedAt }
            NoteSortMode.CREATED -> notes.sortedByDescending { it.createdAt }
        }
    }
}


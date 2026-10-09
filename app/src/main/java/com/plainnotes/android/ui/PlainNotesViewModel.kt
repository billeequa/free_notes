package com.plainnotes.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plainnotes.android.data.NotesRepository
import com.plainnotes.android.model.EditableNote
import com.plainnotes.android.model.NoteDocument
import com.plainnotes.android.model.NoteCategory
import com.plainnotes.android.data.TodoItem
import com.plainnotes.android.data.DoubleXDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ThemeMode(val storageValue: String, val label: String) {
    LIGHT("light", "Paper"),
    LIGHT_2("light_2", "Sunshine"),
    LIGHT_3("light_3", "Sage"),
    DARK_1("dark_1", "Forest"),
    DARK_2("dark_2", "Charcoal"),
    DARK_3("dark_3", "Midnight"),
    SNOW("snow", "Snow"),
    ROSE("rose", "Rose"),
    LAVENDER("lavender", "Lavender"),
    AMOLED("amoled", "OLED black");

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
    val doubleXEnabled: Boolean = false,
    val showReader: Boolean = false,
    val doubleXPromptDate: LocalDate? = null,
    val isLoading: Boolean = true,
    val notes: List<NoteDocument> = emptyList(),
    val journals: List<NoteDocument> = emptyList(),
    val trash: List<NoteDocument> = emptyList(),
    val trashLoading: Boolean = false,
    val todos: List<TodoItem> = emptyList(),
    val todosLoaded: Boolean = false,
    val todoError: String? = null,
    val statusMessage: String? = null,
)

class PlainNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotesRepository(application)
    private val todoMutex = Mutex()
    private val todoActions = TodoActionQueue(viewModelScope)
    private var editorSession: NoteEditorSession? = null
    private var selectedRoot: Uri? = null
    private var refreshJob: Job? = null
    private var trashJob: Job? = null
    private val _uiState = MutableStateFlow(PlainNotesUiState())
    val uiState: StateFlow<PlainNotesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.noteIndex.collect { snapshot ->
                if (snapshot != null && snapshot.folderUri == selectedRoot?.toString()) {
                    _uiState.update { state ->
                        val notes = sortNotes(snapshot.notes, state.noteSortMode)
                        state.copy(selectedFolderName = snapshot.folderName, isLoading = false,
                            notes = notes.filter { it.category == NoteCategory.NOTES },
                            journals = notes.filter { it.category == NoteCategory.JOURNAL })
                    }
                }
            }
        }
        viewModelScope.launch { repository.doubleXEnabled().collectLatest { enabled ->
            _uiState.update { it.copy(doubleXEnabled = enabled, doubleXPromptDate = if (enabled) it.doubleXPromptDate else null) }
        } }
        viewModelScope.launch { repository.showReader().collectLatest { enabled ->
            _uiState.update { it.copy(showReader = enabled) }
        } }
        viewModelScope.launch {
            repository.rootFolderUri().distinctUntilChanged().collectLatest { uri ->
                refreshJob?.cancel()
                trashJob?.cancel()
                selectedRoot = uri
                editorSession = null
                _uiState.update { it.copy(notes = emptyList(), journals = emptyList(), trash = emptyList(),
                    todos = emptyList(), todosLoaded = false, todoError = null) }
                if (uri == null) {
                    _uiState.value = PlainNotesUiState(
                        hasLoadedStorageConfig = true,
                        isStorageConfigured = false,
                        themeMode = _uiState.value.themeMode,
                        fontScale = _uiState.value.fontScale,
                        noteSortMode = _uiState.value.noteSortMode,
                        doubleXEnabled = _uiState.value.doubleXEnabled,
                        showReader = _uiState.value.showReader,
                        isLoading = false,
                    )
                } else {
                    try {
                        val cached = repository.loadCachedNotes(uri)
                        _uiState.update { state ->
                            val notes = sortNotes(cached?.notes.orEmpty(), state.noteSortMode)
                            state.copy(hasLoadedStorageConfig = true, isStorageConfigured = true,
                                selectedFolderName = cached?.folderName, isLoading = cached == null,
                                notes = notes.filter { it.category == NoteCategory.NOTES },
                                journals = notes.filter { it.category == NoteCategory.JOURNAL })
                        }
                        // Tasks are independent of note/Trash scans, including the first index build.
                        loadTodos(force = true)
                        refresh()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _uiState.update { it.copy(hasLoadedStorageConfig = true, isStorageConfigured = true, isLoading = false) }
                        postStatus(error.message ?: "Unable to open the notes folder.")
                    }
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
                _uiState.update { state -> state.copy(noteSortMode = parsed,
                    notes = sortNotes(state.notes, parsed), journals = sortNotes(state.journals, parsed)) }
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(todos = emptyList(), todosLoaded = false, todoError = null) }
                repository.persistRootFolder(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to use that folder.")
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true || selectedRoot == null) return
        refreshJob = viewModelScope.launch {
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
            created
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            postStatus(error.message ?: "Unable to create a new note.")
            null
        }
    }

    suspend fun createJournalNote(): EditableNote? = try {
        val note = repository.createJournalNote()
        note
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        postStatus(error.message ?: "Unable to create journal entry.")
        null
    }

    fun moveNoteCategory(uri: String, category: NoteCategory) {
        viewModelScope.launch {
            try {
                repository.setNoteCategory(uri, category)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to move the entry.")
            }
        }
    }

    suspend fun openDoubleXNote(date: LocalDate = LocalDate.now()): EditableNote? = try {
        val note = repository.findOrCreateDoubleXNote(date)
        _uiState.update { it.copy(doubleXPromptDate = null) }
        note
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        postStatus(error.message ?: "Unable to open Double X note.")
        null
    }

    fun dismissDoubleXPrompt() { _uiState.update { it.copy(doubleXPromptDate = null) } }
    fun setDoubleXEnabled(enabled: Boolean) { viewModelScope.launch { repository.setDoubleXEnabled(enabled) } }
    fun setShowReader(enabled: Boolean) { viewModelScope.launch { repository.setShowReader(enabled) } }

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

    suspend fun openEditor(uri: String): NoteEditorSession? {
        editorSession?.let { session ->
            if (session.initialNote.documentUri.toString() == uri || session.savedNote.documentUri.toString() == uri) return session
        }
        return loadNote(uri)?.let { NoteEditorSession(it).also { session -> editorSession = session } }
    }

    fun closeEditor() { editorSession = null }

    suspend fun saveNote(note: EditableNote): EditableNote? {
        return try {
            val saved = repository.saveNote(note)
            saved
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
                onDone?.invoke()
                // Active entries were removed by the repository without rescanning.
                if (_uiState.value.trash.isNotEmpty()) loadTrash()
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
                _uiState.update { it.copy(trash = it.trash.filterNot { note -> note.id == uriString }) }
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
                _uiState.update { it.copy(trash = it.trash.filterNot { note -> note.id == uriString }) }
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

    fun loadTodos(force: Boolean = false) {
        val root = selectedRoot ?: return
        viewModelScope.launch {
            todoMutex.withLock {
                if (root != selectedRoot || (!force && _uiState.value.todosLoaded)) return@withLock
                try {
                    val items = repository.loadTodos()
                    if (root == selectedRoot) _uiState.update { it.copy(todos = items, todosLoaded = true, todoError = null) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (root == selectedRoot) _uiState.update { it.copy(todosLoaded = false, todoError = error.message ?: "Unable to load to-dos.") }
                }
            }
        }
    }

    fun enqueueTodoChange(change: (List<TodoItem>) -> List<TodoItem>) {
        todoActions.submit { changeTodos(change) }
    }

    suspend fun changeTodos(change: (List<TodoItem>) -> List<TodoItem>): Boolean = withContext(NonCancellable) {
        todoMutex.withLock {
            if (!_uiState.value.todosLoaded) return@withLock false
            val root = selectedRoot
            try {
                val before = _uiState.value.todos
                val updated = change(before)
                repository.saveTodos(updated)
                if (root != selectedRoot) return@withLock false
                _uiState.update { it.copy(todos = updated, todoError = null) }
                if (_uiState.value.doubleXEnabled) {
                    val newCompletions = DoubleXDay.newlyCompleted(before, updated)
                    for (item in newCompletions) {
                        val date = item.completedAt!!.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
                        val count = DoubleXDay.accomplished(updated, date).size
                        if (count > 6 && !repository.hasDoubleXNote(date)) {
                            _uiState.update { it.copy(doubleXPromptDate = date) }
                        }
                    }
                }
                true
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to save the to-do list.")
                false
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to rename the note.")
            }
        }
    }

    private suspend fun refreshState() {
        try {
            repository.listActiveNotes()
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun loadTrash() {
        if (trashJob?.isActive == true) return
        val root = selectedRoot ?: return
        trashJob = viewModelScope.launch {
            _uiState.update { it.copy(trashLoading = it.trash.isEmpty()) }
            try {
                val notes = repository.listTrashedNotes()
                if (root == selectedRoot) _uiState.update { it.copy(trash = notes) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postStatus(error.message ?: "Unable to refresh notes.")
            } finally {
                _uiState.update { it.copy(trashLoading = false) }
            }
        }
    }

    fun onResume() {
        if (_uiState.value.hasLoadedStorageConfig && selectedRoot != null) {
            refresh()
            loadTodos(force = true)
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

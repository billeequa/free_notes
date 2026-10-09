package com.plainnotes.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plainnotes.android.reader.BookEntry
import com.plainnotes.android.reader.BookLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ReaderLibraryState(
    val books: List<BookEntry> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Retains summaries across pager disposal; no Publications or full covers are retained. */
internal class ReaderLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val library by lazy { BookLibrary(application) }
    private val _state = MutableStateFlow(ReaderLibraryState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var loadedFolders: List<Uri>? = null
    private var reloadRequested = false

    @Synchronized
    fun load(force: Boolean = false) {
        if (job?.isActive == true) {
            reloadRequested = reloadRequested || force
            return
        }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val folders = library.folders()
                // Settings can change folders while this tab is disposed. Checking the cached
                // preference is cheap; unchanged tab entries never rescan EPUB directories.
                if (!force && _state.value.loaded && folders == loadedFolders) return@launch
                _state.update { it.copy(loading = !it.loaded) }
                val books = library.scan()
                loadedFolders = folders
                _state.update { it.copy(books = books, loaded = true, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Cannot refresh books.") }
            } finally {
                _state.update { it.copy(loading = false) }
                synchronized(this@ReaderLibraryViewModel) {
                    job = null
                    if (reloadRequested) {
                        reloadRequested = false
                        load(force = true)
                    }
                }
            }
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try { library.addFolder(uri); load(force = true) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(error = error.message ?: "Cannot access this folder.") } }
        }
    }
}

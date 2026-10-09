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

    fun load(force: Boolean = false) {
        if (job?.isActive == true || (!force && _state.value.loaded)) return
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = !it.loaded) }
            try {
                val books = library.scan()
                _state.update { it.copy(books = books, loaded = true, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Cannot refresh books.") }
            } finally {
                _state.update { it.copy(loading = false) }
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

package com.plainnotes.android.ui

import com.plainnotes.android.data.TodoItem
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TodoState(
    val items: List<TodoItem> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

class TodoSession(
    private val scope: CoroutineScope,
    private val read: suspend () -> List<TodoItem>,
    private val write: suspend (List<TodoItem>) -> Unit,
) {
    private val _state = MutableStateFlow(TodoState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var revision = 0L
    private var savedRevision = 0L

    fun reset() { _state.value = TodoState(); revision = 0; savedRevision = 0 }

    fun load() {
        if (_state.value.loaded || _state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        scope.launch {
            try {
                val items = read()
                _state.value = TodoState(items = items, loaded = true)
                // Also flush any recovered local draft, even when the list is empty.
                revision++
                flush()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, error = error.message ?: "Unable to open the list.")
            }
        }
    }

    fun add(text: String) { if (text.isNotBlank()) change { it + TodoItem(text = text.trim()) } }
    fun complete(id: String) = change { items -> items.map {
        if (it.id == id && it.completedAt == null) it.copy(completedAt = OffsetDateTime.now()) else it
    } }
    fun reopen(id: String) = change { items -> items.map { if (it.id == id) it.copy(completedAt = null) else it } }
    fun flag(id: String) = change { items -> items.map { if (it.id == id) it.copy(flagged = !it.flagged) else it } }
    fun delete(id: String) = change { items -> items.filterNot { it.id == id } }

    private fun change(transform: (List<TodoItem>) -> List<TodoItem>) {
        if (!_state.value.loaded) return
        val next = transform(_state.value.items)
        if (next == _state.value.items) return
        revision++
        _state.value = _state.value.copy(items = next)
        scope.launch { flush() }
    }

    fun retry() { if (_state.value.loaded) scope.launch { flush() } else load() }

    suspend fun flush(): Boolean = scope.async {
        mutex.withLock {
            try {
                while (savedRevision != revision) {
                    val snapshot = _state.value.items
                    val snapshotRevision = revision
                    _state.value = _state.value.copy(saving = true, error = null)
                    write(snapshot)
                    savedRevision = snapshotRevision
                }
                _state.value = _state.value.copy(saving = false)
                true
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(saving = false, error = error.message ?: "Save failed. Tap Retry.")
                false
            }
        }
    }.await()
}

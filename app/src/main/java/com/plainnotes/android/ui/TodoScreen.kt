package com.plainnotes.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.plainnotes.android.data.TodoItem
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun HomeTabs(todoSelected: Boolean, onNotes: () -> Unit, onTodos: () -> Unit) {
    Column(Modifier.statusBarsPadding()) {
        TabRow(selectedTabIndex = if (todoSelected) 1 else 0) {
            Tab(selected = !todoSelected, onClick = onNotes, text = { Text("Notes") })
            Tab(selected = todoSelected, onClick = onTodos, text = { Text("To-do") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TodoScreen(
    state: PlainNotesUiState,
    viewModel: PlainNotesViewModel,
    snackbar: SnackbarHostState,
    onNotes: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items = state.todos.sortedBy { it.addedAt.toInstant() }
    val listState = rememberLazyListState()
    var positioned by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadTodos() }
    LaunchedEffect(state.todosLoaded) {
        if (state.todosLoaded && !positioned) {
            val index = items.indexOfLast { it.completedAt == null }.takeIf { it >= 0 } ?: items.lastIndex
            if (index >= 0) listState.scrollToItem(index)
            positioned = true
        }
    }
    fun change(transform: (List<TodoItem>) -> List<TodoItem>) {
        if (busy) return
        scope.launch {
            busy = true
            try { viewModel.changeTodos(transform) } finally { busy = false }
        }
    }
    fun complete(id: String) = change { todos ->
        todos.map { if (it.id == id && it.completedAt == null) it.copy(completedAt = OffsetDateTime.now()) else it }
    }
    Scaffold(
        topBar = { HomeTabs(true, onNotes, {}) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.todosLoaded) FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, "Add to-do")
            }
        },
    ) { padding ->
        when {
            state.todoError != null -> Column(Modifier.padding(padding).padding(24.dp)) {
                Text(state.todoError)
                TextButton(onClick = viewModel::loadTodos) { Text("Retry") }
            }
            !state.todosLoaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Tap + to add your first to-do.")
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    val done = item.completedAt != null
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.StartToEnd && !done && !busy) complete(item.id)
                        false // Completion keeps the row in the timeline.
                    })
                    SwipeToDismissBox(
                        state = swipe,
                        enableDismissFromStartToEnd = !done && !busy,
                        enableDismissFromEndToStart = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(Color(0xFF208447)).padding(20.dp), contentAlignment = Alignment.CenterStart) {
                                Icon(Icons.Rounded.Check, "Complete", tint = Color.White)
                            }
                        },
                    ) {
                        Card(Modifier.fillMaxWidth().combinedClickable(
                            onClick = { editingId = item.id },
                            onLongClick = { menuId = item.id },
                        )) {
                            Column(Modifier.padding(16.dp)) {
                                val color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                Text(
                                    text = "${if (item.flagged) "⚑ " else ""}${item.title}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = color,
                                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                                )
                                if (item.description.isNotBlank()) Text(item.description, color = color, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(6.dp))
                                Text("added: ${todoTimestamp(item.addedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("completed: ${item.completedAt?.let(::todoTimestamp) ?: "—"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = null }) {
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuId = null; editingId = item.id })
                                    DropdownMenuItem(text = { Text(if (item.flagged) "Remove flag" else "Flag") }, onClick = {
                                        menuId = null
                                        change { todos -> todos.map { if (it.id == item.id) it.copy(flagged = !it.flagged) else it } }
                                    })
                                    DropdownMenuItem(text = { Text(if (done) "Mark incomplete" else "Complete") }, onClick = {
                                        menuId = null
                                        change { todos -> todos.map { if (it.id == item.id) it.copy(completedAt = if (done) null else OffsetDateTime.now()) else it } }
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuId = null; deleteId = item.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating || editingId != null) {
        val original = items.find { it.id == editingId }
        TodoEditDialog(original, onDismiss = { creating = false; editingId = null }) { title, description ->
            val item = original?.copy(title = title, description = description) ?: TodoItem(title = title, description = description)
            val saved = viewModel.changeTodos { todos ->
                if (original == null) todos + item else todos.map { if (it.id == item.id) it.copy(title = title, description = description) else it }
            }
            if (saved) {
                creating = false
                editingId = null
                if (original == null) scope.launch { listState.animateScrollToItem(state.todos.size) }
            }
            saved
        }
    }
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("Delete to-do?") },
        text = { Text("This permanently removes the item and its timestamps.") },
        confirmButton = { TextButton(onClick = {
            val id = deleteId
            deleteId = null
            change { it.filterNot { todo -> todo.id == id } }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
    )
}

@Composable
private fun TodoEditDialog(original: TodoItem?, onDismiss: () -> Unit, onSave: suspend (String, String) -> Boolean) {
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var description by rememberSaveable(original?.id) { mutableStateOf(original?.description.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (original == null) "Add to-do" else "Edit to-do") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, enabled = !saving)
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, minLines = 3, maxLines = 8, enabled = !saving)
                if (error) Text("Could not save. Your text is still here; try again.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && !saving, onClick = {
            scope.launch {
                saving = true
                try { error = !onSave(title.trim(), description) } finally { saving = false }
            }
        }) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun todoTimestamp(time: OffsetDateTime): String = time.atZoneSameInstant(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

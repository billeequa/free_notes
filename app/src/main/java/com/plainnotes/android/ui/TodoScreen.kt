package com.plainnotes.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VerticalAlignBottom
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
    val allItems = state.todos.sortedBy { it.addedAt.toInstant() }
    var filter by rememberSaveable { mutableStateOf("All") }
    val items = allItems.filter {
        when (filter) { "Open" -> it.completedAt == null; "Completed" -> it.completedAt != null; "Flagged" -> it.flagged; else -> true }
    }
    val listState = rememberLazyListState()
    var positioned by rememberSaveable { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newTaskId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.loadTodos() }
    LaunchedEffect(state.todosLoaded) {
        if (state.todosLoaded && !positioned) {
            val index = items.indexOfLast { it.completedAt == null }.takeIf { it >= 0 } ?: items.lastIndex
            if (index >= 0) listState.scrollToItem(index)
            positioned = true
        }
    }
    fun change(transform: (List<TodoItem>) -> List<TodoItem>) = viewModel.enqueueTodoChange(transform)
    fun complete(id: String) = change { todos ->
        todos.map { if (it.id == id && it.completedAt == null) it.copy(completedAt = OffsetDateTime.now()) else it }
    }
    fun jumpToLatest() {
        val index = items.indexOfLast { it.completedAt == null }.takeIf { it >= 0 } ?: items.lastIndex
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
    }
    Scaffold(
        topBar = {
            Column {
                HomeTabs(true, onNotes, {})
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { filterMenu = true }) { Text("$filter ▾") }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        listOf("All", "Open", "Completed", "Flagged").forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                filter = label
                                filterMenu = false
                                scope.launch { listState.scrollToItem(0) }
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Jump to latest") }, onClick = {
                            filterMenu = false
                            jumpToLatest()
                        })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.todosLoaded) FloatingActionButton(onClick = { newTaskId = java.util.UUID.randomUUID().toString(); creating = true }) {
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
                Text(if (allItems.isEmpty()) "Tap + to add your first to-do." else "No ${filter.lowercase()} tasks.")
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
                        if (value == SwipeToDismissBoxValue.StartToEnd && !done) complete(item.id)
                        false // Completion keeps the row in the timeline.
                    })
                    SwipeToDismissBox(
                        state = swipe,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                        enableDismissFromStartToEnd = !done,
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
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                val color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = done, onCheckedChange = { checked ->
                                        change { todos -> todos.map { todo ->
                                            if (todo.id == item.id) todo.copy(completedAt = if (checked) todo.completedAt ?: OffsetDateTime.now() else null) else todo
                                        } }
                                    }, modifier = Modifier.semantics { contentDescription = "Completion: ${item.title}" })
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = "${if (item.flagged) "⚑ " else ""}${item.title}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = color,
                                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                                )

                                }
                                if (item.description.isNotBlank()) Text(item.description, color = color, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(item.completedAt?.let { "Completed: ${todoTimestamp(it)}" } ?: "Added: ${todoTimestamp(item.addedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val original = allItems.find { it.id == editingId }
        TodoEditDialog(original, onDismiss = { creating = false; editingId = null }) { title, description, addAnother ->
            val item = original?.copy(title = title, description = description) ?: TodoItem(id = newTaskId, title = title, description = description)
            val saved = viewModel.changeTodos { todos ->
                if (todos.none { it.id == item.id }) todos + item else todos.map { if (it.id == item.id) it.copy(title = title, description = description) else it }
            }
            if (saved) {
                if (addAnother) newTaskId = java.util.UUID.randomUUID().toString()
                creating = addAnother
                editingId = null
                if (original == null) {
                    filter = "All"
                    scope.launch { listState.animateScrollToItem(state.todos.size) }
                }
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
private fun TodoEditDialog(
    original: TodoItem?,
    onDismiss: () -> Unit,
    onSave: suspend (String, String, Boolean) -> Boolean,
) {
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var description by rememberSaveable(original?.id) { mutableStateOf(original?.description.orEmpty()) }
    var detailsExpanded by rememberSaveable(original?.id) { mutableStateOf(!original?.description.isNullOrBlank()) }
    var discardConfirmation by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    var focusRequest by remember { mutableStateOf(0) }
    fun requestDismiss() {
        if (saving) return
        if (title != original?.title.orEmpty() || description != original?.description.orEmpty()) discardConfirmation = true
        else onDismiss()
    }
    fun save(addAnother: Boolean) {
        if (saving || title.isBlank()) return
        saving = true
        scope.launch {
            try {
                error = !onSave(title.trim(), description, addAnother)
                if (!error && addAnother) {
                    title = ""
                    description = ""
                    detailsExpanded = false
                    focusRequest += 1
                }
            } finally { saving = false }
        }
    }
    AlertDialog(
        onDismissRequest = ::requestDismiss,
        title = { Text(if (original == null) "Add to-do" else "Edit to-do") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LaunchedEffect(focusRequest) {
                    withFrameNanos { }
                    titleFocus.requestFocus()
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, enabled = !saving,
                    modifier = Modifier.focusRequester(titleFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save(false) }))
                TextButton(enabled = !saving, onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "Hide description" else "Add description")
                }
                if (detailsExpanded) OutlinedTextField(description, { description = it },
                    label = { Text("Description (optional)") }, minLines = 3, maxLines = 8, enabled = !saving)
                if (error) Text("Could not save. Your text is still here; try again.", color = MaterialTheme.colorScheme.error)
                if (original == null) TextButton(enabled = title.isNotBlank() && !saving, onClick = { save(true) }) {
                    Text("Save and add another")
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && !saving, onClick = { save(false) }) {
            Text(if (saving) "Saving…" else "Save")
        } },
        dismissButton = { TextButton(enabled = !saving, onClick = ::requestDismiss) { Text("Cancel") } },
    )
    if (discardConfirmation) AlertDialog(
        onDismissRequest = { discardConfirmation = false },
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved title and description will be discarded.") },
        confirmButton = { TextButton(onClick = { discardConfirmation = false; onDismiss() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discardConfirmation = false }) { Text("Keep editing") } },
    )
}

private fun todoTimestamp(time: OffsetDateTime): String = time.atZoneSameInstant(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))



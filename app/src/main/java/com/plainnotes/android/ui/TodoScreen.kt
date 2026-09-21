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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import com.plainnotes.android.data.TodoItem
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun HomeTabs(todoSelected: Boolean, onNotes: () -> Unit, onTodos: () -> Unit, actions: @Composable () -> Unit = {}) {
    Column(Modifier.statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        TabRow(selectedTabIndex = if (todoSelected) 1 else 0, modifier = Modifier.weight(1f)) {
            Tab(selected = !todoSelected, onClick = onNotes, text = { Text("Notes") })
            Tab(selected = todoSelected, onClick = onTodos, text = { Text("To-do") })
        }
        actions()
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
    var filter by rememberSaveable { mutableStateOf("Normal") }
    val filteredItems = allItems.filter {
        when (filter) { "Open" -> it.completedAt == null; "Completed" -> it.completedAt != null; "Flagged" -> it.flagged; else -> true }
    }
    val completed = allItems.filter { it.completedAt != null }.sortedBy { it.completedAt!!.toInstant() }
    val open = allItems.filter { it.completedAt == null }
    val items = if (filter == "Normal") completed + open else filteredItems
    val listState = rememberLazyListState()
    var revealedId by remember { mutableStateOf<String?>(null) }
    var positioned by rememberSaveable { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newTaskId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.loadTodos() }
    LaunchedEffect(state.todosLoaded, filter) {
        if (state.todosLoaded && !positioned) {
            val index = if (filter == "Normal") completed.size else 0
            if (index >= 0) listState.scrollToItem(index)
            positioned = true
        }
    }
    fun change(transform: (List<TodoItem>) -> List<TodoItem>) = viewModel.enqueueTodoChange(transform)
    fun complete(id: String) = change { todos ->
        todos.map { if (it.id == id && it.completedAt == null) it.copy(completedAt = OffsetDateTime.now()) else it }
    }
    fun jumpToLatest() {
        val index = if (filter == "Normal") completed.size else 0
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
    }
    Scaffold(
        topBar = {
            HomeTabs(true, onNotes, {}) {
                Box {
                    TextButton(onClick = { filterMenu = true }) { Text("$filter ▾") }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        listOf("Normal", "All", "Open", "Completed", "Flagged").forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                positioned = false
                                revealedId = null
                                filter = label
                                filterMenu = false
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Jump to open items") }, onClick = {
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
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val viewportHeight = maxHeight
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    val done = item.completedAt != null
                    val revealed = revealedId == item.id && !done
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (revealed) IconButton(
                            onClick = { revealedId = null; complete(item.id) },
                            modifier = Modifier.size(56.dp).background(Color(0xFF208447), RoundedCornerShape(12.dp)),
                        ) { Icon(Icons.Rounded.Check, "Confirm completion: ${item.title}", tint = Color.White) }
                        Card(Modifier.weight(1f).todoGestures(
                            onTap = { if (revealed) revealedId = null else editingId = item.id },
                            onMenu = { revealedId = null; menuId = item.id },
                            onReveal = { revealedId = if (done) null else item.id },
                            onClose = { revealedId = null },
                            onBack = { revealedId = null; onNotes() },
                        )) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                val color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = "${if (item.flagged) "⚑ " else ""}${item.title}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = color,
                                )

                                    IconButton(onClick = { menuId = item.id }) { Icon(Icons.Rounded.MoreVert, "Task actions") }
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
                                    DropdownMenuItem(text = { Text(if (done) "Mark incomplete" else "Show completion check") }, onClick = {
                                        menuId = null
                                        if (done) change { todos -> todos.map { if (it.id == item.id) it.copy(completedAt = null) else it } }
                                        else revealedId = item.id
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuId = null; deleteId = item.id })
                                }
                            }
                        }
                    }
                }
                if (filter == "Normal") {
                    if (open.isEmpty()) item(key = "open-empty") {
                        Text("No open tasks. Scroll up for completed tasks.", Modifier.padding(16.dp))
                    }
                    item(key = "normal-space") { Spacer(Modifier.height(viewportHeight)) }
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
                    filter = "Normal"
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



private fun Modifier.todoGestures(
    onTap: () -> Unit, onMenu: () -> Unit, onReveal: () -> Unit,
    onClose: () -> Unit, onBack: () -> Unit,
): Modifier = semantics {
    onClick("Edit task") { onTap(); true }
    onLongClick("Task actions") { onMenu(); true }
}.pointerInput(onTap, onMenu, onReveal, onClose, onBack) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var horizontal = false
        var held = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed || event.changes.count { it.pressed } > 1) break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!horizontal && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                if (abs(dy) >= abs(dx)) break
                horizontal = true
                held = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
            }
            if (horizontal) change.consume()
            if (!change.pressed) {
                if (horizontal) {
                    if (held && dx >= 72.dp.toPx()) onBack()
                    else if (!held && dx >= 32.dp.toPx()) onReveal()
                    else if (!held && dx <= -32.dp.toPx()) onClose()
                } else {
                    change.consume()
                    if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) onMenu()
                    else onTap()
                }
                break
            }
        }
    }
}

package com.plainnotes.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import com.plainnotes.android.data.TodoItem
import com.plainnotes.android.data.TodoFlag
import com.plainnotes.android.data.flagMask
import com.plainnotes.android.data.flagSymbols
import com.plainnotes.android.data.isFlagged
import com.plainnotes.android.data.withFlag
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

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
        when (filter) { "Open" -> it.completedAt == null; "Completed" -> it.completedAt != null; "Flagged" -> it.isFlagged; else -> true }
    }
    val completed = allItems.filter { it.completedAt != null }.sortedBy { it.completedAt!!.toInstant() }
    val open = allItems.filter { it.completedAt == null }
    val items = if (filter == "Normal") completed + open else filteredItems
    val listState = rememberLazyListState()
    val openHeights = remember { mutableStateMapOf<String, Int>() }
    var revealedId by remember { mutableStateOf<String?>(null) }
    var checkboxConfirmId by remember { mutableStateOf<String?>(null) }
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
            val index = if (filter == "Normal" && allItems.isNotEmpty()) completed.size + 1 else 0
            if (index >= 0) listState.scrollToItem(index)
            positioned = true
        }
    }
    fun change(transform: (List<TodoItem>) -> List<TodoItem>) = viewModel.enqueueTodoChange(transform)
    fun complete(id: String) = change { todos ->
        todos.map { if (it.id == id && it.completedAt == null) it.copy(completedAt = OffsetDateTime.now()) else it }
    }
    fun jumpToLatest() {
        val index = if (filter == "Normal" && allItems.isNotEmpty()) completed.size + 1 else 0
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.todosLoaded) FloatingActionButton(
                onClick = { newTaskId = java.util.UUID.randomUUID().toString(); creating = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.navigationBarsPadding().offset(y = 4.dp),
            ) {
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
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val viewportHeight = maxHeight
            val knownOpenHeight = with(LocalDensity.current) { open.sumOf { openHeights[it.id] ?: 0 }.toDp() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().pointerInput(onNotes) {
                    var distance = 0f
                    var startedWithReveal = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            distance = 0f
                            startedWithReveal = revealedId != null || checkboxConfirmId != null
                        },
                        onDragEnd = {
                            if (startedWithReveal) { revealedId = null; checkboxConfirmId = null }
                            else if (distance > 56.dp.toPx()) onNotes()
                        },
                        onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                    )
                },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp,
                    bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "filter-header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("View", style = MaterialTheme.typography.labelLarge)
                Box {
                    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable { filterMenu = true }) {
                        Text(filter, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
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
                }
                if (items.isEmpty()) item(key = "empty") {
                    Text(if (allItems.isEmpty()) "Tap + to add your first to-do." else "No ${filter.lowercase()} tasks.", Modifier.padding(24.dp))
                }
                items(items, key = { it.id }) { item ->
                    val done = item.completedAt != null
                    val flagColors = todoFlagColors(item.flags, MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                    val revealed = revealedId == item.id && !done
                    val checkboxPending = checkboxConfirmId == item.id && !done
                    val completionPending = revealed || checkboxPending
                    val revealWidth = with(LocalDensity.current) { 64.dp.toPx() }
                    var dragOffset by remember(item.id) { mutableStateOf<Float?>(null) }
                    var gestureStartedWithReveal by remember(item.id) { mutableStateOf(false) }
                    val settledOffset by animateFloatAsState(
                        targetValue = dragOffset ?: when {
                            revealed -> -revealWidth
                            checkboxPending -> revealWidth
                            else -> 0f
                        },
                        animationSpec = tween(if (dragOffset != null) 0 else 160), label = "completion reveal",
                    )
                    val offset = dragOffset ?: settledOffset
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .onSizeChanged { if (!done) openHeights[item.id] = it.height }) {
                        if (!done && offset < -1f) IconButton(
                            onClick = { revealedId = null; complete(item.id) },
                            enabled = revealed,
                            modifier = Modifier.align(Alignment.CenterEnd).size(56.dp)
                                .graphicsLayer { alpha = (-offset / revealWidth).coerceIn(0f, 1f) }
                                .background(Color(0xFF208447), RoundedCornerShape(12.dp)),
                        ) { Icon(Icons.Rounded.Check, "Confirm completion: ${item.title}", tint = Color.White) }
                        if (!done && offset > 1f) IconButton(
                            onClick = { checkboxConfirmId = null; complete(item.id) },
                            enabled = checkboxPending,
                            modifier = Modifier.align(Alignment.CenterStart).size(56.dp)
                                .graphicsLayer { alpha = (offset / revealWidth).coerceIn(0f, 1f) }
                                .background(Color(0xFF208447), RoundedCornerShape(12.dp)),
                        ) { Icon(Icons.Rounded.Check, "Confirm completion: ${item.title}", tint = Color.White) }
                        Card(Modifier.fillMaxWidth().graphicsLayer { translationX = offset }.todoGestures(
                            onTap = {
                                if (revealed) revealedId = null
                                else if (checkboxPending) checkboxConfirmId = null
                                else editingId = item.id
                            },
                            onMenu = { revealedId = null; checkboxConfirmId = null; menuId = item.id },
                            onDragStart = {
                                gestureStartedWithReveal = revealed || checkboxPending
                                dragOffset = settledOffset
                            },
                            onDrag = { amount -> if (!done) {
                                val min = if (gestureStartedWithReveal && checkboxPending) 0f else -revealWidth
                                val max = if (gestureStartedWithReveal && checkboxPending) revealWidth else 0f
                                dragOffset = ((dragOffset ?: settledOffset) + amount).coerceIn(min, max)
                            } },
                            onDragEnd = { distance ->
                                if (gestureStartedWithReveal) {
                                    revealedId = null
                                    checkboxConfirmId = null
                                } else if (distance >= revealWidth) { revealedId = null; onNotes() }
                                else if (!done && (dragOffset ?: 0f) <= -revealWidth / 2) revealedId = item.id
                                else revealedId = null
                                dragOffset = null
                                gestureStartedWithReveal = false
                            },
                            onDragCancel = { dragOffset = null; gestureStartedWithReveal = false },
                        ), colors = CardDefaults.cardColors(
                            containerColor = flagColors?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = flagColors?.content ?: MaterialTheme.colorScheme.onSurface,
                        )) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                val color = if (done) flagColors?.completedContent ?: MaterialTheme.colorScheme.onSurfaceVariant
                                    else flagColors?.content ?: MaterialTheme.colorScheme.onSurface
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = done || completionPending,
                                        onCheckedChange = {
                                            if (done) change { todos -> todos.map { if (it.id == item.id) it.copy(completedAt = null) else it } }
                                            else if (completionPending) { revealedId = null; checkboxConfirmId = null }
                                            else { revealedId = null; checkboxConfirmId = item.id }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = if (completionPending)
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                            else MaterialTheme.colorScheme.primary,
                                        ),
                                        modifier = Modifier.semantics {
                                            contentDescription = when {
                                                done -> "Reopen: ${item.title}"
                                                completionPending -> "Cancel completion: ${item.title}"
                                                else -> "Show completion confirmation: ${item.title}"
                                            }
                                            stateDescription = when {
                                                done -> "Completed"
                                                completionPending -> "Awaiting confirmation"
                                                else -> "Incomplete"
                                            }
                                        },
                                    )
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = listOf(item.flagSymbols, item.title).filter { it.isNotEmpty() }.joinToString(" "),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = color,
                                )

                                    IconButton(onClick = { menuId = item.id }) { Icon(Icons.Rounded.MoreVert, "Task actions") }
                                }
                                if (item.description.isNotBlank()) Text(item.description, color = color, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(item.completedAt?.let { "Completed: ${todoTimestamp(it)}" } ?: "Added: ${todoTimestamp(item.addedAt)}", style = MaterialTheme.typography.labelSmall, color = flagColors?.completedContent ?: MaterialTheme.colorScheme.onSurfaceVariant)
                                DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = null }) {
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuId = null; editingId = item.id })
                                    TodoFlag.entries.forEach { flag ->
                                        DropdownMenuItem(
                                            text = { Text("Mark ${if (flag == TodoFlag.LONG_TERM) "as " else ""}${flag.label} ${flag.emoji}") },
                                            trailingIcon = { if (flag in item.flags) Icon(Icons.Rounded.Check, "Selected") },
                                            onClick = {
                                                menuId = null
                                                change { todos -> todos.map { if (it.id == item.id) it.withFlag(flag) else it } }
                                            },
                                        )
                                    }
                                    if (item.flagged) DropdownMenuItem(text = { Text("Remove legacy flag") }, onClick = {
                                        menuId = null
                                        change { todos -> todos.map { if (it.id == item.id) it.copy(flagged = false) else it } }
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
                    if (open.isNotEmpty()) item(key = "normal-space") {
                        Spacer(Modifier.height((viewportHeight - knownOpenHeight - 88.dp - (open.size * 8).dp)
                            .coerceAtLeast(0.dp)))
                    }
                }
            }
            }
        }
    }
    if (creating || editingId != null) {
        val original = allItems.find { it.id == editingId }
        TodoEditDialog(original, onDismiss = { creating = false; editingId = null }) { title, description, flags ->
            val item = TodoItem(id = newTaskId, title = title, description = description, flags = flags)
            val saved = viewModel.changeTodos { todos ->
                if (original == null) {
                    if (todos.none { it.id == item.id }) todos + item else todos
                } else todos.map { if (it.id == original.id) it.copy(
                    title = title, description = description, flags = flags,
                    flagged = it.flagged && flags.isEmpty(),
                ) else it }
            }
            if (saved) {
                creating = false
                editingId = null
                if (original == null) {
                    filter = "Normal"
                    scope.launch { listState.animateScrollToItem(state.todos.size + 1) }
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
    onSave: suspend (String, String, Set<TodoFlag>) -> Boolean,
) {
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var description by rememberSaveable(original?.id) { mutableStateOf(original?.description.orEmpty()) }
    var flagMask by rememberSaveable(original?.id) { mutableStateOf(original?.flags?.flagMask() ?: 0) }
    var detailsExpanded by rememberSaveable(original?.id) { mutableStateOf(!original?.description.isNullOrBlank()) }
    var discardConfirmation by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    fun requestDismiss() {
        if (saving) return
        if (title != original?.title.orEmpty() || description != original?.description.orEmpty() ||
            flagMask != (original?.flags?.flagMask() ?: 0)) discardConfirmation = true
        else onDismiss()
    }
    fun save() {
        if (saving || title.isBlank()) return
        saving = true
        scope.launch {
            try {
                error = !onSave(title.trim(), description, TodoFlag.entries.filter { flagMask and it.bit != 0 }.toSet())
            } finally { saving = false }
        }
    }
    AlertDialog(
        onDismissRequest = ::requestDismiss,
        title = { Text(if (original == null) "Add to-do" else "Edit to-do") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    titleFocus.requestFocus()
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, enabled = !saving,
                    modifier = Modifier.focusRequester(titleFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }))
                TextButton(enabled = !saving, onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "Hide description" else "Add description")
                }
                if (detailsExpanded) OutlinedTextField(description, { description = it },
                    label = { Text("Description (optional)") }, minLines = 3, maxLines = 8, enabled = !saving)
                if (error) Text("Could not save. Your text is still here; try again.", color = MaterialTheme.colorScheme.error)
                Column {
                    TodoFlag.entries.forEach { flag ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = flagMask and flag.bit != 0, enabled = !saving, role = Role.Checkbox) {
                                flagMask = flagMask xor flag.bit
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = flagMask and flag.bit != 0, onCheckedChange = null, enabled = !saving)
                            Text("Mark ${if (flag == TodoFlag.LONG_TERM) "as " else ""}${flag.label} ${flag.emoji}", Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && !saving, onClick = { save() }) {
            Text(if (saving) "Saving…" else "Save")
        } },
        dismissButton = { TextButton(enabled = !saving, onClick = ::requestDismiss) { Text("Cancel") } },
    )
    if (discardConfirmation) AlertDialog(
        onDismissRequest = { discardConfirmation = false },
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved title, description, and flags will be discarded.") },
        confirmButton = { TextButton(onClick = { discardConfirmation = false; onDismiss() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discardConfirmation = false }) { Text("Keep editing") } },
    )
}

private fun todoTimestamp(time: OffsetDateTime): String = time.atZoneSameInstant(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))



@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.todoGestures(
    onTap: () -> Unit, onMenu: () -> Unit,
    onDragStart: () -> Unit, onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit, onDragCancel: () -> Unit,
): Modifier {
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    return combinedClickable(onClick = onTap, onLongClick = onMenu)
        .pointerInput(Unit) {
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f; start() },
                onDragEnd = { end(distance) },
                onDragCancel = { cancel() },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    distance += amount
                    drag(amount)
                },
            )
        }
}

package com.plainnotes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plainnotes.android.data.TodoItem
import com.plainnotes.android.ui.theme.plainNotesTopAppBarColors
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(session: TodoSession, onBack: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val selected = state.items.find { it.id == selectedId }
    LaunchedEffect(session) { session.load() }

    fun back() {
        if (leaving) return
        leaving = true
        scope.launch {
            try { if (session.flush()) onBack() } finally { leaving = false }
        }
    }
    fun add() {
        if (draft.isBlank() || !state.loaded) return
        session.add(draft)
        draft = ""
        scope.launch { listState.animateScrollToItem(0) }
    }
    BackHandler { back() }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = plainNotesTopAppBarColors(),
                title = { Text("To-do") },
                navigationIcon = { IconButton(onClick = { back() }, enabled = !leaving) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notes")
                } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (state.error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = session::retry) { Text("Retry") }
            }
            if (state.saving) Text("Saving…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // Reverse layout puts newest at the bottom; older history is above it.
                items(state.items.sortedByDescending { it.createdAt.toInstant() }, key = { it.id }) { item ->
                    TodoRow(item, onComplete = { session.complete(item.id) }, onOptions = { selectedId = item.id })
                }
                item(key = "instructions") {
                    Text(
                        if (state.items.isEmpty()) "Add your first item below. Swipe right to complete; hold an item for options."
                        else "Older items are above. Swipe right to complete; hold for options.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Add an item") },
                    enabled = state.loaded, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                IconButton(onClick = { add() }, enabled = state.loaded && draft.isNotBlank()) { Icon(Icons.Rounded.Add, "Add item") }
            }
        }
    }
    if (selected != null) AlertDialog(
        onDismissRequest = { selectedId = null },
        title = { Text("Item options") },
        text = { Column {
            Text(selected.text)
            TextButton(onClick = {
                if (selected.completedAt == null) session.complete(selected.id) else session.reopen(selected.id)
                selectedId = null
            }) { Text(if (selected.completedAt == null) "Complete" else "Reopen") }
            TextButton(onClick = { session.flag(selected.id); selectedId = null }) { Text(if (selected.flagged) "Remove flag" else "Flag") }
            TextButton(onClick = { deleteId = selected.id; selectedId = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { selectedId = null }) { Text("Close") } },
    )
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("Delete this item?") }, text = { Text("It will be removed from your history.") },
        confirmButton = { TextButton(onClick = { session.delete(deleteId!!); deleteId = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(item: TodoItem, onComplete: () -> Unit, onOptions: () -> Unit) {
    var offset by remember(item.id, item.completedAt) { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val currentComplete by rememberUpdatedState(onComplete)
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    Box(Modifier.fillMaxWidth().background(if (offset > 0) Color(0xFF237A3B) else MaterialTheme.colorScheme.background)) {
        if (offset > 0) Icon(Icons.Rounded.Check, "Complete", tint = Color.White, modifier = Modifier.align(Alignment.CenterStart).padding(20.dp))
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(offset.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(item.id, item.completedAt) {
                    if (item.completedAt == null) detectHorizontalDragGestures(
                        onDragEnd = {
                            val complete = offset >= threshold
                            offset = 0f
                            if (complete) currentComplete()
                        },
                        onDragCancel = { offset = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            offset = (offset + amount).coerceIn(0f, threshold * 1.5f)
                        },
                    )
                }
                .combinedClickable(onClick = onOptions, onLongClick = onOptions, onLongClickLabel = "Item options")
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.flagged) Icon(Icons.Rounded.Flag, "Flagged", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                if (item.completedAt != null) Icon(Icons.Rounded.Check, "Completed", tint = Color(0xFF399657), modifier = Modifier.size(20.dp))
                Text(item.text, Modifier.weight(1f).padding(start = if (item.flagged || item.completedAt != null) 8.dp else 0.dp),
                    textDecoration = if (item.completedAt != null) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (item.completedAt != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground)
            }
            Text("Created ${item.createdAt.format(dateFormat)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.completedAt?.let { Text("Completed ${it.format(dateFormat)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

package com.plainnotes.android.ui

import android.content.Intent
import android.graphics.Bitmap
import com.plainnotes.android.reader.CoverThumbnails
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.plainnotes.android.reader.BookEntry
import com.plainnotes.android.reader.BookLibrary
import com.plainnotes.android.reader.EpubReaderActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderLibraryScreen(onSettings: () -> Unit, active: Boolean = true, scrollToTopRequest: Int = 0) {
    val context = LocalContext.current
    val model: ReaderLibraryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val books = state.books
    val loading = state.loading
    val error = state.error
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<BookEntry?>(null) }
    var consumedTopRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopRequest, loading, books.isNotEmpty()) {
        if (scrollToTopRequest != consumedTopRequest && !loading && books.isNotEmpty()) {
            listState.scrollToItem(0)
            consumedTopRequest = scrollToTopRequest
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val isActive by rememberUpdatedState(active)
    DisposableEffect(lifecycleOwner, model) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) {
                paused = false
                if (isActive) model.load(force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) model.addFolder(uri)
    }
    LaunchedEffect(active) { if (active) model.load() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        when {
            loading -> { ReaderLibraryHeader(loading) { model.load(force = true) }; CircularProgressIndicator() }
            books.isEmpty() -> Box(Modifier.fillMaxSize()) {
                ReaderLibraryHeader(loading) { model.load(force = true) }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No EPUBs found in your books folders.")
                    Button(onClick = { picker.launch(null) }) { Text("Add Books Folder") }
                    TextButton(onClick = onSettings) { Text("Open Settings") }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                item(key = "library-controls") { ReaderLibraryHeader(loading) { model.load(force = true) } }
                items(books, key = { it.uri.toString() }) { book ->
                    Card(Modifier.fillMaxWidth().combinedClickable(
                        onLongClickLabel = "Rename or edit book details",
                        onLongClick = { editing = book },
                        onClick = {
                            context.startActivity(Intent(context, EpubReaderActivity::class.java)
                                .putExtra("book_uri", book.uri.toString()).putExtra("book_display_title", book.displayTitle))
                        })) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val density = LocalDensity.current
                            val width = with(density) { 60.dp.roundToPx() }
                            val height = with(density) { 90.dp.roundToPx() }
                            val cover by produceState<Bitmap?>(null, book.coverPath, width, height) {
                                value = withContext(Dispatchers.IO) {
                                    book.coverPath?.let { CoverThumbnails.load(it, width, height) }
                                }
                            }
                            if (cover != null) Image(cover!!.asImageBitmap(), book.displayTitle, Modifier.size(width = 60.dp, height = 90.dp))
                            else if (book.coverPath != null) Spacer(Modifier.size(width = 60.dp, height = 90.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(book.displayTitle, style = MaterialTheme.typography.titleMedium)
                                if (book.author.isNotBlank()) Text(book.author)
                                val lastRead = if (book.lastRead == 0L) "Not started" else {
                                    val date = Instant.ofEpochMilli(book.lastRead).atZone(ZoneId.systemDefault())
                                    "${(book.progress * 100).toInt()}% read · Last read ${date.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}"
                                }
                                Text(lastRead,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { book ->
        BookDetailsDialog(book, model, onDismiss = { editing = null })
    }

}

@Composable
private fun ReaderLibraryHeader(loading: Boolean, onRefresh: () -> Unit) {
    // An ordinary first list item, matching Notes' Sort by row; never a sticky overlay.
    var fontSettings by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("E Reader", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = { fontSettings = true }) { Text("Aa") }
        FilledTonalButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
    }
    if (fontSettings) ReaderFontSizeDialog(onDismiss = { fontSettings = false })
}

@Composable
private fun BookDetailsDialog(book: BookEntry, model: ReaderLibraryViewModel, onDismiss: () -> Unit) {
    var stem by rememberSaveable(book.uri.toString()) { mutableStateOf(book.filename.dropLast(5)) }
    var title by rememberSaveable(book.uri.toString()) { mutableStateOf(book.title) }
    var author by rememberSaveable(book.uri.toString()) { mutableStateOf(book.author) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var needsAccess by remember { mutableStateOf(false) }
    val permissionPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            if (uri != book.folderUri) {
                error = "Choose this book’s original books folder to allow changes."
            } else try {
                model.allowFolderChanges(uri)
                error = "Folder access updated. Tap Save to try again."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Cannot allow changes to this folder." }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Edit book") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(stem, { value -> stem = if (value.endsWith(".epub", true)) value.dropLast(5) else value },
                    label = { Text("Filename") }, suffix = { Text(book.filename.takeLast(5)) },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, label = { Text("Book title") },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(author, { author = it }, label = { Text("Author") },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                Text("Title and author are saved into the EPUB. The filename is shown in the library only when both are empty.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (needsAccess) FilledTonalButton(onClick = { permissionPicker.launch(book.folderUri) }, enabled = !saving) {
                        Text("Allow folder changes")
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !saving && stem.isNotBlank(), onClick = {
                saving = true; error = null; needsAccess = false
                scope.launch {
                    try { model.saveDetails(book, stem, title, author); onDismiss() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: SecurityException) { needsAccess = true; error = "Allow changes to this books folder, then tap Save again." }
                    catch (e: Exception) {
                        needsAccess = e is java.io.IOException || generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException }
                        error = e.message ?: "Cannot save this EPUB. Your changes are still here."
                    }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { FilledTonalButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}

@Composable
fun BooksFoldersSettings() {
    val context = LocalContext.current
    val library = remember { BookLibrary(context) }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try { library.addFolder(uri); refresh++ }
            catch (e: Exception) { message = e.message }
        }
    }
    LaunchedEffect(refresh) { folders = library.folders() }
    Text("Books Folders", style = MaterialTheme.typography.titleMedium)
    folders.forEach { uri ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString(), Modifier.weight(1f))
            TextButton(onClick = { scope.launch { library.removeFolder(uri); refresh++ } }) { Text("Remove") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch(null) }) { Text("Add Folder") }
        TextButton(onClick = { scope.launch {
            try { val count = library.scan().size; message = "$count EPUBs found" }
            catch (e: Exception) { message = e.message }
        } }) { Text("Refresh Library") }
    }
    if (message != null) Text(message!!, style = MaterialTheme.typography.bodyMedium)
}

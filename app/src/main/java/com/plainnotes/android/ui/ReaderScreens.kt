package com.plainnotes.android.ui

import android.content.Intent
import android.graphics.Bitmap
import com.plainnotes.android.reader.CoverThumbnails
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.Image
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

@Composable
fun ReaderLibraryScreen(onSettings: () -> Unit, active: Boolean = true, scrollToTopRequest: Int = 0) {
    val context = LocalContext.current
    val model: ReaderLibraryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val books = state.books
    val loading = state.loading
    val error = state.error
    val listState = rememberLazyListState()
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("E Reader", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { model.load(force = true) }) { Text("Refresh") }
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        when {
            loading -> CircularProgressIndicator()
            books.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("No EPUBs found in your books folders.")
                Button(onClick = { picker.launch(null) }) { Text("Add Books Folder") }
                TextButton(onClick = onSettings) { Text("Open Settings") }
            }
            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                items(books, key = { it.uri.toString() }) { book ->
                    Card(Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(context, EpubReaderActivity::class.java)
                            .putExtra("book_uri", book.uri.toString()))
                    }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val density = LocalDensity.current
                            val width = with(density) { 60.dp.roundToPx() }
                            val height = with(density) { 90.dp.roundToPx() }
                            val cover by produceState<Bitmap?>(null, book.coverPath, width, height) {
                                value = withContext(Dispatchers.IO) {
                                    book.coverPath?.let { CoverThumbnails.load(it, width, height) }
                                }
                            }
                            if (cover != null) Image(cover!!.asImageBitmap(), book.title, Modifier.size(width = 60.dp, height = 90.dp))
                            else if (book.coverPath != null) Spacer(Modifier.size(width = 60.dp, height = 90.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(book.title, style = MaterialTheme.typography.titleMedium)
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

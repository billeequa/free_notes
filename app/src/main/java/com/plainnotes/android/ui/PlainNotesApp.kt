package com.plainnotes.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plainnotes.android.model.EditableNote
import com.plainnotes.android.model.NoteDocument
import com.plainnotes.android.ui.components.NoteBodyEditor
import com.plainnotes.android.ui.theme.PlainNotesTheme
import com.plainnotes.android.ui.theme.plainNotesTopAppBarColors
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppScreen {
    Notes,
    Todos,
    Settings,
    Trash,
}

@Composable
fun PlainNotesApp(viewModel: PlainNotesViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val screenStateHolder = rememberSaveableStateHolder()
    var currentScreenName by rememberSaveable { mutableStateOf(AppScreen.Notes.name) }
    var editingNoteUri by rememberSaveable { mutableStateOf<String?>(null) }
    var editorStartsInEditMode by rememberSaveable { mutableStateOf(false) }
    val currentScreen = AppScreen.valueOf(currentScreenName)
    val pagerState = rememberPagerState { 2 }
    val folderPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let {
            screenStateHolder.removeState(AppScreen.Notes.name)
            screenStateHolder.removeState(AppScreen.Todos.name)
            viewModel.onFolderPicked(it)
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusMessage()
        }
    }

    PlainNotesTheme(
        themeMode = uiState.themeMode,
        fontScale = uiState.fontScale,
    ) {
        if (editingNoteUri == null) {
            when (currentScreen) {
                AppScreen.Settings -> BackHandler { currentScreenName = AppScreen.Notes.name }
                AppScreen.Trash -> BackHandler { currentScreenName = AppScreen.Settings.name }
                AppScreen.Todos -> BackHandler { currentScreenName = AppScreen.Notes.name }
                AppScreen.Notes -> if (pagerState.currentPage == 1) BackHandler { scope.launch { pagerState.animateScrollToPage(0) } }
            }
        }

        if (!uiState.hasLoadedStorageConfig) {
            CenterLoading(Modifier.fillMaxSize())
            return@PlainNotesTheme
        }

        if (!uiState.isStorageConfigured) {
            SetupScreen(
                onPickFolder = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxSize(),
            )
            return@PlainNotesTheme
        }

        if (editingNoteUri != null) {
            NoteEditorRoute(
                noteUri = editingNoteUri.orEmpty(),
                startInEditMode = editorStartsInEditMode,
                fontScale = uiState.fontScale,
                onNoteUriChanged = { editingNoteUri = it },
                onBack = {
                    viewModel.closeEditor()
                    editingNoteUri = null
                    editorStartsInEditMode = false
                },
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
            )
            return@PlainNotesTheme
        }

        screenStateHolder.SaveableStateProvider(currentScreenName) {
            when (currentScreen) {
                AppScreen.Notes, AppScreen.Todos -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    if (page == 0) NotesHomeScreen(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    onOpenSettings = { currentScreenName = AppScreen.Settings.name },
                    onOpenTodos = { scope.launch { pagerState.animateScrollToPage(1) } },
                    onOpenNote = {
                        editorStartsInEditMode = false
                        editingNoteUri = it.documentUri.toString()
                    },
                    onCreateNote = {
                        scope.launch {
                            val created = viewModel.createNote()
                            editorStartsInEditMode = true
                            editingNoteUri = created?.documentUri?.toString()
                        }
                    },
                    onRenameNote = viewModel::renameNote,
                    onMoveToTrash = { viewModel.moveToTrash(it) },
                    noteSortMode = uiState.noteSortMode,
                    onSortSelected = viewModel::setNoteSortMode,
                )
    
                else TodoScreen(
                    state = uiState,
                    viewModel = viewModel,
                    snackbar = snackbarHostState,
                    onNotes = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                }
    
                AppScreen.Settings -> SettingsScreen(
                    selectedFolderName = uiState.selectedFolderName,
                    themeMode = uiState.themeMode,
                    fontScale = uiState.fontScale,
                    onBack = { currentScreenName = AppScreen.Notes.name },
                    onPickFolder = { folderPicker.launch(null) },
                                        onOpenTrash = { currentScreenName = AppScreen.Trash.name },
                    onThemeSelected = viewModel::setThemeMode,
                    onFontScaleSelected = viewModel::setFontScale,
                    modifier = Modifier.fillMaxSize(),
                )
    
                AppScreen.Trash -> TrashScreen(
                    notes = uiState.trash,
                    isLoading = uiState.isLoading,
                    onBack = { currentScreenName = AppScreen.Settings.name },
                    onRestore = { viewModel.restoreFromTrash(it.documentUri.toString()) },
                    onDeletePermanently = { viewModel.deletePermanently(it.documentUri.toString()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Choose a notes folder",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Create or choose a Documents folder named PlainNotes, then the app will store note files there with Trash and Exports subfolders.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onPickFolder) {
                Text("Pick notes folder")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesHomeScreen(
    uiState: PlainNotesUiState,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenTodos: () -> Unit,
    onOpenNote: (NoteDocument) -> Unit,
    onCreateNote: () -> Unit,
    onRenameNote: (String, String) -> Unit,
    onMoveToTrash: (String) -> Unit,
    noteSortMode: NoteSortMode,
    onSortSelected: (NoteSortMode) -> Unit,
) {
    Scaffold(
        topBar = { HomeTabs(false, {}, onOpenTodos) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NotesScreen(
            notes = uiState.notes,
            isLoading = uiState.isLoading,
            onOpen = onOpenNote,
            onRenameNote = onRenameNote,
            onMoveToTrash = onMoveToTrash,
            noteSortMode = noteSortMode,
            onSortSelected = onSortSelected,
            modifier = Modifier.fillMaxSize(),
        )

            FloatingActionButton(
                onClick = onOpenSettings,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                )
            }

            FloatingActionButton(
                onClick = onCreateNote,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "New note",
                )
            }
        }
    }
}

@Composable
private fun NotesScreen(
    notes: List<NoteDocument>,
    isLoading: Boolean,
    onOpen: (NoteDocument) -> Unit,
    onRenameNote: (String, String) -> Unit,
    onMoveToTrash: (String) -> Unit,
    noteSortMode: NoteSortMode,
    onSortSelected: (NoteSortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedMenuNoteId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<NoteDocument?>(null) }

    if (isLoading) {
        CenterLoading(modifier)
        return
    }

    if (notes.isEmpty()) {
        EmptyState(
            title = "No notes yet",
            body = "Tap the plus button to create your first note.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sort by",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Normal,
                )
                SortModeBox(
                    sortMode = noteSortMode,
                    onSortSelected = onSortSelected,
                )
            }
        }
        items(notes, key = { it.id }) { note ->
            NoteListRow(
                note = note,
                isMenuExpanded = selectedMenuNoteId == note.id,
                onOpen = { onOpen(note) },
                onShowMenu = { selectedMenuNoteId = note.id },
                onDismissMenu = { selectedMenuNoteId = null },
                onRename = {
                    renameTarget = note
                    selectedMenuNoteId = null
                },
                onMoveToTrash = {
                    onMoveToTrash(note.documentUri.toString())
                    selectedMenuNoteId = null
                },
            )
        }
    }

    if (renameTarget != null) {
        RenameDialog(
            initialTitle = renameTarget?.title.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                onRenameNote(renameTarget!!.documentUri.toString(), newTitle)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun SortModeBox(
    sortMode: NoteSortMode,
    onSortSelected: (NoteSortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.clickable { expanded = true },
        ) {
            Text(
                text = when (sortMode) {
                    NoteSortMode.MODIFIED -> "Modified"
                    NoteSortMode.CREATED -> "Created"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Modified") },
                onClick = {
                    expanded = false
                    onSortSelected(NoteSortMode.MODIFIED)
                },
            )
            DropdownMenuItem(
                text = { Text("Created") },
                onClick = {
                    expanded = false
                    onSortSelected(NoteSortMode.CREATED)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteListRow(
    note: NoteDocument,
    isMenuExpanded: Boolean,
    onOpen: () -> Unit,
    onShowMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMoveToTrash: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = onShowMenu,
                )
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = shortDate(note.modifiedAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = onDismissMenu,
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = { Text("Move to trash") },
                    onClick = onMoveToTrash,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    selectedFolderName: String?,
    themeMode: ThemeMode,
    fontScale: Float,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenTrash: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onFontScaleSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = plainNotesTopAppBarColors(),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = modifier
                .padding(innerPadding)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Notes live in",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedFolderName ?: "No folder selected",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Button(onClick = onPickFolder) {
                        Text("Change")
                    }
                }
                HorizontalDivider()
                Text(
                    text = "Themes",
                    style = MaterialTheme.typography.titleMedium,
                )
                ChoiceRow(
                    options = ThemeMode.entries.map { it.label to (themeMode == it) },
                    onSelected = { label -> onThemeSelected(ThemeMode.entries.first { it.label == label }) },
                )
                HorizontalDivider()
                Text(
                    text = "Font size",
                    style = MaterialTheme.typography.titleMedium,
                )
                ChoiceRow(
                    options = listOf(
                        "Small" to (fontScale == 0.9f),
                        "Small+" to (fontScale == 0.95f),
                        "Medium" to (fontScale == 1.0f),
                        "Medium+" to (fontScale == 1.075f),
                        "Large" to (fontScale == 1.15f),
                        "XL" to (fontScale == 1.3f),
                    ),
                    onSelected = { label ->
                        onFontScaleSelected(
                            when (label) {
                                "Small" -> 0.9f
                                "Small+" -> 0.95f
                                "Medium+" -> 1.075f
                                "Large" -> 1.15f
                                "XL" -> 1.3f
                                else -> 1.0f
                            },
                        )
                    },
                )
                HorizontalDivider()
                Text("Notes and tasks save automatically to your selected folder.", style = MaterialTheme.typography.bodyMedium)
                UpdateSettings()
                Button(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) {
                    Text("Open trash")
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, Boolean>>,
    onSelected: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, selected) ->
            FilterChip(
                selected = selected,
                onClick = { onSelected(label) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(
    notes: List<NoteDocument>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRestore: (NoteDocument) -> Unit,
    onDeletePermanently: (NoteDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingPermanentDelete by remember { mutableStateOf<NoteDocument?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = plainNotesTopAppBarColors(),
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (isLoading) {
            CenterLoading(modifier.padding(innerPadding))
            return@Scaffold
        }

        if (notes.isEmpty()) {
            EmptyState(
                title = "Trash is empty",
                body = "Deleted notes wait here until you restore or remove them permanently.",
                modifier = modifier.padding(innerPadding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(notes, key = { it.id }) { note ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = note.displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = shortDate(note.modifiedAt),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRestore(note) }) {
                                Text("Restore")
                            }
                            TextButton(onClick = { pendingPermanentDelete = note }) {
                                Text("Delete forever")
                            }
                        }
                    }
                }
            }
        }

        if (pendingPermanentDelete != null) {
            AlertDialog(
                onDismissRequest = { pendingPermanentDelete = null },
                title = { Text("Delete forever?") },
                text = {
                    Text("This will permanently remove \"${pendingPermanentDelete?.displayTitle}\" from Trash.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeletePermanently(pendingPermanentDelete!!)
                            pendingPermanentDelete = null
                        },
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPermanentDelete = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename note") },
        text = {
            TextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NoteEditorRoute(
    noteUri: String,
    startInEditMode: Boolean,
    fontScale: Float,
    onNoteUriChanged: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: PlainNotesViewModel,
    snackbarHostState: SnackbarHostState,
) {
    var session by remember { mutableStateOf<NoteEditorSession?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(noteUri) {
        // Updating the saved route URI must not replace a live native editor.
        val current = session
        if (current != null && (current.initialNote.documentUri.toString() == noteUri ||
                current.savedNote.documentUri.toString() == noteUri)) return@LaunchedEffect
        isLoading = true
        session = viewModel.openEditor(noteUri)
        isLoading = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            isLoading -> CenterLoading(Modifier.padding(innerPadding))
            session == null -> {
                BackHandler(onBack = onBack)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Note unavailable",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "This note could not be opened. It may have been deleted or moved.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onBack) {
                        Text("Back to notes")
                    }
                }
            }

            else -> NoteEditorScreen(
                session = session!!,
                startInEditMode = startInEditMode,
                fontScale = fontScale,
                onBack = onBack,
                onMoveToTrash = { uri -> viewModel.moveToTrash(uri, onBack) },
                onSave = { updated ->
                    viewModel.saveNote(updated)?.also { saved ->
                        onNoteUriChanged(saved.documentUri.toString())
                    }
                },
                modifier = Modifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorScreen(
    session: NoteEditorSession,
    startInEditMode: Boolean,
    fontScale: Float,
    onBack: () -> Unit,
    onMoveToTrash: (String) -> Unit,
    onSave: suspend (EditableNote) -> EditableNote?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val note = session.initialNote
    var title by session.title
    var body by session.body
    val lastSavedTitle = session.savedNote.title
    val lastSavedBody = session.savedNote.body
    val lastSavedAt = session.savedNote.modifiedAt
    val createdAt = session.savedNote.createdAt
    val isSaving = session.isSaving
    var renameDialogOpen by remember(note.documentUri.toString()) { mutableStateOf(false) }
    var pendingUrl by remember(note.documentUri.toString()) { mutableStateOf<String?>(null) }
    var confirmTrashDialogOpen by remember(note.documentUri.toString()) { mutableStateOf(false) }
    var shouldFocusBodyEditor by remember(note.documentUri.toString()) { mutableStateOf(startInEditMode) }
    var requestedSelection by remember(note.documentUri.toString()) {
        mutableStateOf<Int?>(if (startInEditMode) note.body.length else null)
    }
    var noteScrollY by rememberSaveable(note.documentUri.toString()) { mutableStateOf(0) }
    var showNoteInfo by remember { mutableStateOf(false) }

    suspend fun saveIfNeeded(): Boolean = session.save(onSave)

    LaunchedEffect(title, body) {
        if (title == lastSavedTitle && body == lastSavedBody) {
            return@LaunchedEffect
        }
        delay(1500)
        saveIfNeeded()
    }

    BackHandler {
        scope.launch {
            if (saveIfNeeded()) {
                keyboardController?.hide()
                focusManager.clearFocus()
                onBack()
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, note.documentUri.toString()) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                lifecycleOwner.lifecycleScope.launch { saveIfNeeded() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = plainNotesTopAppBarColors(),
                expandedHeight = 48.dp,
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { renameDialogOpen = true })
                        if (isSaving || session.isDirty) Text(
                            text = when {
                                isSaving -> "Saving…"
                                session.saveFailed && session.isDirty -> "Not saved — Retry"
                                session.isDirty -> "Unsaved changes"
                                else -> "Saved"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (session.saveFailed && session.isDirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = session.isDirty && !isSaving) {
                                scope.launch { saveIfNeeded() }
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (saveIfNeeded()) onBack()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showNoteInfo = true }) {
                        Icon(Icons.Rounded.Info, "Note information")
                    }
                    IconButton(
                        onClick = {
                            confirmTrashDialogOpen = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Move to trash",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 18.dp),
            ) {
                NoteBodyEditor(
                    value = body,
                    onValueChange = { body = it },
                    onUrlTapped = { pendingUrl = it },
                    shouldRequestFocus = shouldFocusBodyEditor,
                    onFocusHandled = { shouldFocusBodyEditor = false },
                    requestedSelection = requestedSelection,
                    onRequestedSelectionHandled = { requestedSelection = null },
                    initialScrollY = noteScrollY,
                    onSelectionChanged = { requestedSelection = it },
                    onScrollChanged = { noteScrollY = it },
                    fontScale = fontScale,
                    modifier = Modifier.fillMaxSize(),
                )

            }
        }
    }

    if (showNoteInfo) AlertDialog(
        onDismissRequest = { showNoteInfo = false },
        title = { Text("Note information") },
        text = { Text("Created ${shortDateTime(createdAt)}\nLast saved ${shortDateTime(lastSavedAt)}") },
        confirmButton = { TextButton(onClick = { showNoteInfo = false }) { Text("Close") } },
    )

    if (renameDialogOpen) {
        RenameDialog(
            initialTitle = title,
            onDismiss = { renameDialogOpen = false },
            onConfirm = { newTitle ->
                title = newTitle.trim()
                renameDialogOpen = false
            },
        )
    }

    if (pendingUrl != null) {
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text("Open link?") },
            text = { Text(pendingUrl!!) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = pendingUrl!!
                        pendingUrl = null
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            )
                            context.startActivity(
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: Exception) {
                        }
                    },
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmTrashDialogOpen) {
        AlertDialog(
            onDismissRequest = { confirmTrashDialogOpen = false },
            title = { Text("Move note to trash?") },
            text = { Text("You can restore it later from Trash.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTrashDialogOpen = false
                        scope.launch {
                            if (saveIfNeeded()) onMoveToTrash(session.savedNote.documentUri.toString())
                        }
                    },
                ) {
                    Text("Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrashDialogOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun CenterLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun shortDate(value: OffsetDateTime): String {
    return value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

private fun shortDateTime(value: OffsetDateTime): String {
    return value.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}




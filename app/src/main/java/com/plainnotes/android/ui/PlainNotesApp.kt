package com.plainnotes.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
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
import com.plainnotes.android.ui.components.NoteBodyViewer
import com.plainnotes.android.ui.theme.PlainNotesTheme
import com.plainnotes.android.ui.theme.plainNotesTopAppBarColors
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    var currentScreenName by rememberSaveable { mutableStateOf(AppScreen.Notes.name) }
    var editingNoteUri by rememberSaveable { mutableStateOf<String?>(null) }
    var editorStartsInEditMode by rememberSaveable { mutableStateOf(false) }
    val currentScreen = AppScreen.valueOf(currentScreenName)
    val folderPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderPicked)
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
                AppScreen.Todos -> Unit
                AppScreen.Notes -> Unit
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
                onMoveToTrash = {
                    viewModel.moveToTrash(editingNoteUri.orEmpty()) {
                        editingNoteUri = null
                        editorStartsInEditMode = false
                    }
                },
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
            )
            return@PlainNotesTheme
        }

        when (currentScreen) {
            AppScreen.Notes -> NotesHomeScreen(
                uiState = uiState,
                snackbarHostState = snackbarHostState,
                onOpenSettings = { currentScreenName = AppScreen.Settings.name },
                onOpenTodos = { currentScreenName = AppScreen.Todos.name },
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

            AppScreen.Todos -> TodoScreen(viewModel.todos, onBack = { currentScreenName = AppScreen.Notes.name })

            AppScreen.Settings -> SettingsScreen(
                selectedFolderName = uiState.selectedFolderName,
                themeMode = uiState.themeMode,
                fontScale = uiState.fontScale,
                onBack = { currentScreenName = AppScreen.Notes.name },
                onPickFolder = { folderPicker.launch(null) },
                onExport = viewModel::exportNotes,
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
        topBar = {
            CenterAlignedTopAppBar(
                colors = plainNotesTopAppBarColors(),
                title = { Text("Notes") },
                navigationIcon = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                actions = { TextButton(onClick = onOpenTodos) { Text("To-do") } },
            )
        },
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
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
    onExport: () -> Unit,
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
                modifier = Modifier.fillMaxWidth(),
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
                    options = listOf(
                        "Light" to (themeMode == ThemeMode.LIGHT),
                        "Light 2" to (themeMode == ThemeMode.LIGHT_2),
                        "Light 3" to (themeMode == ThemeMode.LIGHT_3),
                        "Dark theme 1" to (themeMode == ThemeMode.DARK_1),
                        "Dark theme 2" to (themeMode == ThemeMode.DARK_2),
                        "Dark theme 3" to (themeMode == ThemeMode.DARK_3),
                    ),
                    onSelected = { label ->
                        onThemeSelected(
                            when (label) {
                                "Light" -> ThemeMode.LIGHT
                                "Light 2" -> ThemeMode.LIGHT_2
                                "Light 3" -> ThemeMode.LIGHT_3
                                "Dark theme 2" -> ThemeMode.DARK_2
                                "Dark theme 3" -> ThemeMode.DARK_3
                                else -> ThemeMode.DARK_1
                            },
                        )
                    },
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
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("Export zip")
                }
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
    onMoveToTrash: () -> Unit,
    viewModel: PlainNotesViewModel,
    snackbarHostState: SnackbarHostState,
) {
    var session by remember(noteUri) { mutableStateOf<NoteEditorSession?>(null) }
    var loading by remember(noteUri) { mutableStateOf(true) }
    LaunchedEffect(noteUri) {
        session = viewModel.openEditor(noteUri)
        loading = false
    }
    BackHandler(enabled = session == null, onBack = onBack)
    when {
        loading -> CenterLoading()
        session == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Note unavailable. Check your folder access and try again.")
            Button(onClick = onBack) { Text("Back to notes") }
        }
        else -> NoteEditorScreen(session!!, startInEditMode, fontScale, onBack, onMoveToTrash, snackbarHostState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorScreen(
    session: NoteEditorSession,
    startInEditMode: Boolean,
    fontScale: Float,
    onBack: () -> Unit,
    onMoveToTrash: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val note by session.note.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var rename by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var focusBody by remember { mutableStateOf(startInEditMode) }
    var selection by rememberSaveable { mutableStateOf(note.body.length) }
    var scrollY by rememberSaveable { mutableStateOf(0) }
    var leaving by remember { mutableStateOf(false) }

    fun leave(afterSave: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            try {
                if (session.save()) afterSave()
            } finally {
                leaving = false
            }
        }
    }
    // Android dismisses the IME first; the next Back goes straight to the list.
    BackHandler { leave(onBack) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) session.saveInBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.saveInBackground()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = plainNotesTopAppBarColors(),
                title = {
                    Column {
                        Text(note.title.ifBlank { "Untitled" }, Modifier.clickable { rename = true }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, Modifier.clickable { session.saveInBackground() }, style = MaterialTheme.typography.labelSmall,
                            color = if (status.startsWith("Save failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { leave(onBack) }, enabled = !leaving) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notes")
                    }
                },
                actions = {
                    IconButton(onClick = { trash = true }, enabled = !leaving) { Icon(Icons.Rounded.Delete, "Move to trash") }
                },
            )
        },
    ) { padding ->
        NoteBodyEditor(
            value = note.body,
            onValueChange = { session.edit(body = it) },
            onUrlTapped = { pendingUrl = it },
            shouldRequestFocus = focusBody,
            onFocusHandled = { focusBody = false },
            requestedSelection = selection,
            onRequestedSelectionHandled = {},
            initialScrollY = scrollY,
            onSelectionChanged = { selection = it },
            onScrollChanged = { scrollY = it },
            fontScale = fontScale,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .imePadding().padding(horizontal = 18.dp),
        )
    }
    if (rename) RenameDialog(note.title, { rename = false }) {
        session.edit(title = it)
        rename = false
    }
    if (pendingUrl != null) AlertDialog(
        onDismissRequest = { pendingUrl = null },
        title = { Text("Open link?") },
        text = { Text(pendingUrl!!) },
        confirmButton = { TextButton(onClick = {
            val url = pendingUrl!!
            pendingUrl = null
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { pendingUrl = null }) { Text("Cancel") } },
    )
    if (trash) AlertDialog(
        onDismissRequest = { trash = false },
        title = { Text("Move note to trash?") },
        text = { Text("You can restore it later from Trash.") },
        confirmButton = { TextButton(onClick = { trash = false; leave(onMoveToTrash) }) { Text("Trash") } },
        dismissButton = { TextButton(onClick = { trash = false }) { Text("Cancel") } },
    )
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


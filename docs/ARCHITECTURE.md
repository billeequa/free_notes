# Architecture

## Design goals

Jnotes v1 is intentionally local, portable, and small. The selected folder and its text files are the primary data store. App state can be reconstructed from those files without a database or account.

The project is a single Android application module with four practical layers:

```text
Compose screens and native text widgets
                 |
          PlainNotesViewModel
                 |
          NotesRepository
          /             \
 NoteFileParser     AppSettingsRepository
        |                    |
 UTF-8 text files       Android DataStore
 through Android SAF
```

## UI layer

`PlainNotesApp.kt` owns the application screens and navigation state:

- Notes list
- Note reader/editor
- Settings and export
- Trash
- First-run folder selection

Most UI is Jetpack Compose and Material 3. Note body rendering is implemented in `NoteEditors.kt` with `AndroidView` wrappers around native `TextView`, `ScrollView`, and `EditText` widgets. This hybrid approach preserves Android's text selection and context menu while allowing custom read/edit modes and link behavior.

Read mode presents a scrollable document without a cursor. Tapping normal text enters edit mode at the tapped character position. Tapping a URL asks for confirmation before sending an `ACTION_VIEW` browser intent. In edit mode, Android's native selection menu remains available, with an additional `Open link` action when the selection overlaps a URL.

URL spans are not rebuilt on every keystroke. Link detection refreshes when a URL-looking token is completed, such as after whitespace or punctuation, reducing work during long-form typing.

## State layer

`PlainNotesViewModel.kt` exposes a `StateFlow<PlainNotesUiState>` containing:

- Whether folder configuration has loaded
- The selected folder name
- Active and trashed note lists
- Theme, font-size, and sorting preferences
- Loading and status-message state

The `ViewModel` coordinates repository calls and converts storage errors into user-facing messages. Coroutine cancellation is rethrown rather than displayed as an error.

Editor-local state lives in the note screen because it includes transient cursor, scroll, title, body, and save state. Autosaves are serialized with a `Mutex` so idle autosave, back navigation, and lifecycle saves cannot write the same note concurrently.

## Data layer

`NotesRepository.kt` is the boundary between application logic and Android document storage. It uses:

- `ACTION_OPEN_DOCUMENT_TREE` for folder selection
- Persisted read/write URI permission for future launches
- `DocumentFile` for folder traversal, creation, rename, move, and deletion
- `ContentResolver` streams for UTF-8 reads and writes

The app never assumes a raw filesystem path. That is important on modern Android, where a selected folder may be provided by local storage, an SD card, or another document provider.

### Save and rename method

The filename is derived from the immutable creation timestamp and current title:

```text
yyyyMMdd-HHmmss-title-slug.txt
```

Saving follows this sequence:

1. Resolve the current document by filename, falling back to its stored document URI.
2. Generate a collision-safe desired filename.
3. Try the provider's direct rename operation.
4. If direct rename is unavailable, create a replacement file with the desired name.
5. Serialize and write the complete note.
6. Delete the old document only after the replacement has been written.
7. Reload the saved document and return its current URI and filename to the editor.

This fallback is necessary because Storage Access Framework providers differ in how reliably they implement rename operations.

### Trash and restore method

Trash is a real subfolder rather than a database flag. Moving to Trash copies the document into `Trash/` using a collision-safe name, then deletes the original. Restore performs the inverse operation. Permanent deletion is available only from Trash and requires confirmation in the UI.

### Export method

Export creates a timestamped ZIP in `Exports/` and streams every active root-level `.txt` note into the archive. Trashed notes and previous exports are excluded.

## Parser

`NoteFileParser.kt` converts between files and `NoteTextContent`. It preserves everything after the metadata separator as the body. The parser is intentionally forgiving:

- Current local timestamps use `yyyy-MM-dd HH:mm:ss`.
- Older ISO-8601 timestamps with an offset remain readable.
- Missing dates fall back to document metadata or the current time.
- Files without recognized headers remain usable as body text.
- Missing titles are inferred from the first non-empty body line or filename.

## Preferences

`AppSettingsRepository.kt` uses Android DataStore for data that does not belong inside a note:

- Persisted root-folder URI
- Theme selection
- Font scale
- Sort mode

No note content is stored in DataStore.

## Deliberate constraints

V1 does not include cloud synchronization, accounts, rich text, attachments, reminders, collaboration, folders inside the app, or an internal database. These constraints keep the files portable and the behavior understandable.

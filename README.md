# Jnotes

Jnotes is a small, ad-free Android notes app built around ordinary UTF-8 text files. It has no account, cloud service, database, analytics, or advertising SDK. The user chooses a folder, and that folder remains the source of truth.

The current planned release is **1.3.3**. See [release notes](releases/NOTES.md) and [update/signing setup](docs/UPDATES.md).

## Features

- Plain-text notes stored in a user-selected folder
- Direct, single-tap editing with normal Android text selection
- Serialized autosave after 1.5 seconds idle and when leaving/backgrounding; drafts survive rotation and failed saves stay open
- Notes / To-do tabs; chronological task history with descriptions, flags, and creation/completion timestamps
- Swipe right to complete; tap to edit; long-press for edit, flag, completion/reopen, or delete
- Human-readable titles, creation dates, and modified dates inside every file
- Title-based filenames with stable creation timestamps
- Open links from the native text-selection menu, with confirmation before opening
- Trash, restore, and confirmed permanent deletion
- Automatic writes to the selected notes folder
- Six font sizes and ten themes, including OLED black
- Sorting by creation or modification time
- Forgiving parsing for manually edited and older note files

## Storage layout

Jnotes uses Android's Storage Access Framework. On first launch, the user grants access to a folder. The app creates and maintains this structure:

```text
Selected notes folder/
  20260405-214530-example-title.txt
  to_do_jnotes.txt
  Trash/
  Exports/
    notes-export-2026-04-05-220100.zip
```

Each note is self-contained:

```text
Title: Example title
Created: 2026-04-05 21:45:30
Modified: 2026-04-05 22:01:00

The note body begins here.
```

See [the file-format documentation](docs/FILE_FORMAT.md) for parsing and compatibility details.

## Architecture

The app uses Kotlin, Jetpack Compose, a small `ViewModel`/repository structure, Android DataStore for preferences, and `DocumentFile`/`ContentResolver` for user-owned files. The editor retains its draft in a ViewModel-owned session and deliberately embeds native Android text widgets inside Compose so selection, cursor handling, scrolling, and context-menu behavior remain close to standard Android behavior.

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for component responsibilities and data flows.

## Build

Open the project in Android Studio or build from a terminal with JDK 17 and Android SDK 36 installed:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK will be written to `app/build/outputs/apk/debug/app-debug.apk`. Release signing keys are intentionally not included in the repository. See [BUILDING.md](docs/BUILDING.md) for complete setup, release-build, and signing guidance.

## Privacy

Jnotes does not require internet access to create, read, edit, delete, or export notes. Update checks use GitHub only when requested in Settings. Private release downloads can use the signed-in browser. No note content is sent.


See [device verification](docs/DEVICE_TESTING.md) for the Pixel 4a 5G regression checklist.

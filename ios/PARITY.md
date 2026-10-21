# Android parity and iPhone acceptance

Android reference inspected at `3474722f0da5af7b8ff1c4f031e9de9a94ebc862` (Android 1.5.5-era main). This first iOS client follows the current Android code, not the older architecture document that predates journal/tasks/sync.

| Area | Implemented behavior | Hardware check |
|---|---|---|
| Navigation | Four fixed top tabs, Notes startup, hide E Reader, tap active tab to reposition | Tab labels at all font sizes; swipe tab bar |
| Notes | Create/open/rename, modified/created sort, title/body search, info, trash/restore, ZIP import/export | Open a long note and compare scrolling/selection |
| Editor | One native text view for notes/journal, tap to edit at position, read mode removes cursor, native selection, delayed autosave, lifecycle save, explicit link confirmation | Type at overflow boundary; hide keyboard; verify last lines stay clear of system gesture area |
| Journal | Date titles, Roman-number duplicate entries, editable body, optional Double X Day | Multiple entries on one date |
| Double X Day | Snapshot accomplishments once; real editable headings; prompt after more than six new completions | Edit/remove headings, reopen, confirm they do not regenerate |
| To-do | Title/description, urgent/important/long-term flags on creation and context menu, combined colors and legacy flags | All seven flag combinations in light/dark themes |
| Completion | Checkbox stages completion, checked/lightened checkbox while pending; reveal green confirm on left; swipe left reveals confirm on right; completed checkbox reopens | Cancellation, horizontal/vertical gesture arbitration, rapid taps |
| Task browsing | Normal/All/Open/Completed/Flagged; archive months/current year and older years; recent ten completed; startup at active boundary | Older/more recent completed items, very short and very long active lists |
| Themes | Paper, Sunshine, Sage, Forest, Charcoal, Midnight, Snow, Rose, Lavender, OLED black | Palette comparison and text contrast |
| Storage | Android UTF-8 formats and legacy reads; selected Files folder; durable save journal; stable-ID index | Kill app during save/rename, restart; external provider access revoked |
| Web Sync | Optional; first-sync preview; password login; encrypted records; durable retries; cursor safety; task field merge; conflicts; revision history; managed service discovery | Approved test account; phone↔web↔Android, disconnect/offline/restart cases |
| Ebooks | DRM-free EPUB import, folder import, covers, chapters, font size, saved progress and local-only publication resources | EPUB 2/3 samples, links, images, relative paths, restart/read progression |

## Intentional platform differences

- Text selection, keyboard, Files picker, share sheet and some dialogs are native iOS controls.
- iOS controls background refresh scheduling; foreground sync is the reliable immediate path.
- EPUB folder additions import local copies. Android retains live folder access through its Storage Access Framework. The iOS reader is WebKit-based rather than Readium; advanced EPUB rendering is not claimed to be identical.
- App installation and updates use Xcode/Apple distribution instead of Android APK self-updates.
- Settings exposes equivalent options in an iOS form. Core tab order, card behavior and theme colors follow Android.

## Acceptance sequence for the Mac/iPhone builder

1. Run the XCTest suite with a simulator. Confirm encryption vectors pass.
2. Use local mode without logging in. Create a note, a journal entry and several tasks. Close/reopen; all remain available.
3. Import an Android export ZIP and check multiline text, whitespace, emojis, flags, completed timestamps and Double X metadata. Export it again and check the Android app reads those files.
4. Exercise the completion confirmation from both checkbox and swipe; cancelling must leave the completion timestamp empty. Verify completed titles are gray and flag colors remain.
5. Write a long note, including near the scrolling/keyboard transition. Hide the keyboard with Read, scroll with no cursor, and check autosave after reopening.
6. Mark a journal as Double X Day, edit its inserted headings and reopen. The edited body must remain intact.
7. Read a DRM-free EPUB, change chapters/text size, close and reopen. Verify last-read position/progress and links.
8. With an approved **test notebook**, preview first sync, then test additions/edits/deletions in both directions. Modify the same task on different fields, then on the same field; verify merge and explicit conflict behavior respectively.
9. Turn off network access or log out. Local documents must still launch, read, edit and export. Retry sync after reconnecting; no duplicate uploads or missing pending edits.
10. Verify theme/font sizes on a real iPhone and, if used, an iPad. Automated compilation does not replace this visual and interaction pass.

Do not test destructive sync scenarios on the sole copy of an important notebook. Use the built-in export and a separate test notebook.

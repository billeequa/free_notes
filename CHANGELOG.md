# 1.4.5

Add combinable urgent, important, and long-term to-do flags, distinct theme-aware task colors retained after completion, and creation/edit checkboxes. Preserve legacy black flags and task history while upgrading the to-do file format.

# 1.4.4

- Show a lighter checked To-Do checkbox while completion awaits confirmation; canceling restores the empty box.
- Android Back switches writing to reading in Notes, Journal, and Double X Day, hiding the keyboard, cursor, and selection handles while keeping the note and scroll position.
- Scroll without resuming editing; tap text to place the cursor and write again. Back from reading saves and returns to the list.
- Preserve the transparent gesture bar, five-line end clearance, and failed-save draft/Retry behavior.

# 1.4.3

- Add five font-scaled lines of scrollable space after note text, without saving extra newlines.
- Keep the viewport edge-to-edge and the gesture bar transparent; no fixed bottom ribbon.
- Preserve native selection/caret handling and verify clearance across keyboard-height and text-length transitions.

# 1.4.2

- Keep the shared Notes/Journal editor viewport stable at the keyboard overflow boundary.
- Preserve native word-selection handles across updates and leave range scrolling to Android.
- Insert Double X headings and accomplishments once as editable, standard note text.
- Migrate older Double X entries without discarding writing; preserve edited headings and titles on reopen.
- Document Android selection and viewport constraints and broaden device regression checks across all four sections.

# 1.4.0

- Add a separate Journal tab, with manual moves between Notes and Journal.
- Journal + uses yyyy-mm-dd titles and Roman numerals for additional same-day entries.
- Double X Day marks the first journal for its date, preserves its writing, and appends Double X Day to its date title.
- Existing Double X Day entries appear in Journal automatically.
- The tab window scrolls to keep the selected tab visible.

# 1.3

Compact editor, ten themes, swipe tabs, simplified task cards and filters, conditional timestamps, and validated APK updates. Existing task format preserved; rollback omitted. See releases/NOTES.md.

# Changelog

## v1

- Initial v1 source baseline for Jnotes
- User-owned UTF-8 text-file storage through Android's Storage Access Framework
- Read and edit modes with link handling
- Debounced autosave and lifecycle saves
- Title-based filenames and backward-compatible metadata parsing
- Trash, restore, permanent-delete confirmation, and ZIP export
- Multiple themes, font sizes, and note sorting
- Installable APK at `releases/jnotes_v1.apk`
# 1.3.4

Optional Readium EPUB library and Double X Day notes. Existing note and to-do text formats remain compatible.

## 1.3.5

Fix bottom control overlap, restore the earlier top bar appearance, and keep both sides of To-Do completion confirmation.

## 1.3.6

Draw app content beneath the gesture bar, move E Reader left of Notes, refine note spacing and timestamps, match reader backgrounds and To Do plus-button colors, and clarify Double X Day controls.

## 1.3.7

Give the last Note clearance above floating controls, lower those controls, make an opposite swipe dismiss an open To-Do completion check, reduce Double X overlap, and improve status bar contrast over the tab ribbon.

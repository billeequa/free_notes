# JNotes 1.4.2

Fix the shared Notes/Journal editor jumping between two heights when text just begins to overflow with the keyboard visible. Keep the viewport stable and stop resetting native text layout on each update. Preserve word-selection ranges and leave two-handle selection scrolling to Android.

Double X Day now inserts Things Accomplished, the completed-task snapshot, and Notes on the Day into the note body with standard note formatting. All of that text, and the title, is editable. Existing Double X entries receive the template once above their previous writing. Saving/reopening never regenerates edited or deleted headings or replaces the list with later tasks.

Reviewed Notes, Journal, Ebooks, and To Do for equivalent resize/selection patterns. EPUB uses its persistent Readium navigator; To Do uses Compose text fields. Their existing swipe gestures are unchanged. Added Android-framework regression tests and device checks for the overflow transition and selection handles.

Install over the existing 1.3.4 or later app with the same signing key. Your notes, journals, books, settings, and to-do data remain in place. No uninstall is needed.

# JNotes 1.4.6

- Faster startup from a rebuildable private metadata index. Note bodies load when opened; Trash no longer delays startup.
- Saving updates the affected entry instead of rescanning every note. Save recovery and read-back verification remain enabled.
- Less repeated work when switching tabs, sorting tasks, and loading book covers.
- Layouts, gestures, editing behavior, note/task file formats, filenames, and exports are unchanged. No migration or new files in the selected notes folder.

Install **jnotes-18.apk** over the existing app. The application ID and signing key are unchanged; no uninstall is needed. The first launch builds the private index; subsequent launches reuse it while checking for external changes in the background.

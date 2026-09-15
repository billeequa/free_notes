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


## Unreleased

- Use one persistent native editor, remove the metadata strip and custom keyboard
  spacer, and draw the editor through the bottom system-navigation area.
- Save outside the composition/debounce lifecycle, preserve newer keystrokes,
  keep document identities stable, expose failures/retry, and keep recovery drafts.
- Add Notes > To-do: continuous history, creation/completion dates, right-swipe
  completion, flag/reopen/delete options, and `to_do_jnotes.txt` persistence/export.
- Add Android CI build and save/history regression tests.

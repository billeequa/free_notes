# Editor and to-do verification

Run `./gradlew testDebugUnitTest assembleDebug` (also configured in GitHub Actions).
Tests cover note parsing, to-do text/history round trips and malformed files,
overlapping edits during a save, caller cancellation, save retry, recovery,
completion/reopening and overlapping to-do writes.

Device acceptance checks (API 26+ and API 36; portrait and landscape):

- Open an existing note and tap any text once: cursor and keyboard appear. Scroll,
  select/copy/paste, and use selection's Open link action. New notes focus immediately.
- Type through several autosaves, including rapidly while a slow provider writes.
  Keep typing and verify the cursor and latest text do not jump or revert.
- Rename a title while typing; filename/URI remain stable. Back returns to Notes
  after saving. Hide the keyboard and reopen it without switching editor modes.
- Check the editor extends to the bottom with no app footer, metadata row, or
  large keyboard padding. Check last-line visibility, gesture navigation,
  three-button navigation, landscape cutouts, all themes and large font settings.
- Rotate and background immediately after typing, then reopen. Deny provider access
  and verify save failure remains visible, Back does not discard edits, and Retry
  succeeds after restoring access. Relaunch to recover a draft from a failed write.
- Notes > To-do: add items, verify creation timestamps, scroll upward to older
  entries. Swipe right past the green check threshold to complete; a short drag,
  cancelled drag or vertical scroll must not complete. Completed items stay visible.
- Long-press or tap an item: flag/unflag, complete/reopen, delete with confirmation.
  Check completion timestamps persist across restart and repeated completion does
  not replace the first completion date. Confirm with TalkBack as well.
- Export and inspect the ZIP for the special file. Ensure it never appears as a
  normal note. Corrupt a copy of the special file: loading must fail without edits.

An emulator/device pass is needed to validate keyboard/inset/provider behavior;
unit tests cannot establish those visual and Android framework properties.

# Pixel 4a 5G verification

Build and JVM tests: `./gradlew testDebugUnitTest assembleDebug`.

These manual checks remain required on the device; a successful build does not
verify Android IME, touch, or Storage Access Framework behavior.

1. Use a local Documents folder. Open a long note, scroll to its middle, and tap
   text once. Verify cursor placement and Gboard opening without another tap.
2. Type at the end, including several trailing newlines. Verify the final caret
   remains visible above Gboard without an empty ribbon. Hide/show the keyboard,
   rotate, and repeat using gesture navigation and three-button navigation.
3. Verify there is one app header, with no intermittent band above the body.
   The header stays below the Android status icons. With the keyboard hidden,
   the editor background and scrolling text extend beneath the transparent
   gesture-navigation bar; there must be no reserved bottom ribbon.
4. Type continuously while a save finishes. No typed characters should disappear,
   focus should remain, and the newest draft should subsequently save.
5. Rename while typing, rotate, background immediately after an edit, reopen,
   then use both the app back arrow and system Back. Check disk text each time.
6. Revoke folder access or use an unavailable provider while editing. Leaving
   must not close a dirty note after a failed save. Restore access and retry.
7. Add title-only and multiline to-dos. Check both timestamp labels. Swipe right
   for a green check; the completed row stays visible and becomes muted.
8. Long-press for edit, flag, complete/reopen, and confirmed deletion. Reopen the
   app and check persistence. Newest unfinished item should be the initial
   position; scroll up to older/completed items.
9. Export and inspect the ZIP: ordinary notes and `to_do_jnotes.txt` should be
   present. The special file must not appear as an ordinary note.
10. Test malformed to-do text: show an error and Retry, without replacing the
    file or permitting edits to an empty fallback list.

A debug APK uses a development signing key. Updating an existing installation
requires the same signing key as that installation. Do not uninstall to bypass
this: build/sign with the original key if an in-place update is needed.



## Version 1.2 usability regressions

- Trigger completion, flagging, and deletion in quick succession on a slow provider;
  every accepted action must be applied in order, including after changing tabs.
- Edit a task and press Back, Cancel, or tap outside: choose Keep editing and verify
  both fields survive; only Discard should remove the draft.
- Scroll into history, switch to Notes and back, and rotate: keep the same position.
  The latest shortcut returns to the newest unfinished item in the current filter.
- Add a title-only task using keyboard Done; use Save and add another repeatedly.
  The next title is focused, descriptions remain optional, and failed saves retain text.
- Use the visible checkbox and menu with TalkBack; verify completion and long-press
  still work. Verify the existing main-page floating buttons still overlay entries.
- Force a note save failure: Not saved — Retry remains available under the title.
  Retrying must clear the failure only after a successful save. Note information
  contains timestamps; the separate metadata strip is gone.

## Journal / 1.4.0 upgrade checks

- Upgrade over 1.3.7 with the same signing key; confirm normal notes remain in Notes and existing Double X entries appear only in Journal.
- Long-press a normal note, Move to Journal, restart, then Move to Notes; confirm its title and exact text survive both moves.
- Tap Journal + repeatedly: check yyyy-mm-dd, II, III; restart and create IV. Edit and save text, then reopen it.
- Create a daily journal and write paragraphs; use Double X Day, confirm the same entry and writing are retained with the suffix. Add another journal; repeated Double X opens still use the first.
- Trash and restore both kinds of entries; confirm each returns to its category. Check ZIP export includes journal metadata.
- Enable E Reader and check all four tabs at narrow widths and large font scale. All enabled tabs stay visible; swiping the fixed tab bar changes pages without scrolling the bar. To-Do completion gestures retain their previous behavior.
- Open Double X Day from Notes or the auto prompt; Back should return to Journal. Settings should return to the previously selected home tab.

## Editor insets and fixed tabs / 1.4.1 regressions

- On Android 14 with gesture navigation, open both a normal note and a journal.
  Hide the keyboard and scroll a long entry: text and background must draw
  underneath the gesture handle, with no blank band truncating the viewport.
- Show/hide Gboard, switch themes, rotate, background/resume, and return from an
  EPUB. The editor must remain edge-to-edge; the last caret stays above Gboard.
- Check three-button navigation too. Android may apply its own contrast scrim
  there; that is distinct from a Scaffold reserving blank bottom space.
- With E Reader enabled, verify Ebooks | Notes | Journal | To Do, equally sized
  and visible together. With it disabled, verify Notes | Journal | To Do and
  persistent books data. Notes is selected on a fresh launch in either mode.
- Tap each tab, then swipe left/right on the bar. Only the selected page changes;
  the bar does not move, and swiping past the first/last tab has no effect.
- Swipe page content in Ebooks, Notes, and Journal. In To Do, verify that a left
  swipe reveals right-side completion, checkbox tap reveals left-side completion,
  right swipe still goes directly to Notes, and a swipe with confirmation already
  open only dismisses it. Long-press still opens the task menu.

## Editor transitions / 1.4.2 regressions

- In Notes, Journal, and Double X, start with text that fits with Gboard hidden
  but just overflows with it visible. Add/remove lines at that boundary, including
  trailing blank lines. The viewport must not alternate heights or jump rapidly.
- Long-press a word, adjust both selection handles, copy/paste, and select across
  lines while scrolling. Repeat during autosave and keyboard hide/show. A selected
  range must not be forced to its final character by caret visibility assistance.
- Open an older Double X with writing, and promote a regular journal. Edit both
  headings, the accomplishment list, and the title; save/reopen through both the
  journal card and daily shortcut. Delete headings and reopen: no regenerated or
  duplicate template. Existing writing and whitespace remain underneath.
- Check To Do title/description selection and length transitions with Gboard.
  Repeat completion, dismiss-opposite-swipe, and right-swipe-to-Notes gestures.
- In an EPUB, select text across lines/pages and adjust Readium's native handles.
  Show/hide controls, open/close Contents, rotate, and change font size/theme;
  text must not oscillate and the navigator must retain its place.

JVM Android-framework tests cover stable native editor updates and range-selection
ownership. Device checks above still verify actual Gboard and touch handles.

## End-of-note clearance / 1.4.3

- In Notes, Journal, and Double X, scroll a long document to the bottom. The final
  line should sit five note-line heights above the viewport bottom. Change font
  size: the clearance scales with it. Export/reopen: no synthetic newlines.
- Scroll in the middle: text still draws behind the transparent gesture bar;
  there is no fixed blank strip. Repeat light/dark themes and keyboard hide/show.
- Add/remove lines around the keyboard-visible overflow boundary; the viewport
  and tail padding must remain stable. Select words and drag both handles across
  lines, then type at the end; Gboard must leave the caret visible.
- Switch tabs and rotate; restore document scrolling. To Do swipes stay unchanged.

## Pending checkboxes and Back-to-reading / 1.4.4

- Tap an open task checkbox: while the row slides right, the original checkbox
  shows a lighter check and the left green confirmation appears. Tap the original
  checkbox again, tap the card, or swipe to dismiss: the box returns to empty and
  no completion timestamp or Double X accomplishment is recorded.
- Swipe left for right-side confirmation: verify the same lighter pending check.
  Confirm either side: verify completed appearance, persistence, and timestamp.
  Check TalkBack announces Awaiting confirmation rather than Completed.
- In Notes, Journal, and Double X, type and press system Back once. The keyboard,
  caret, and selection handles disappear, the note stays open, and scrolling
  continues from the same position. Dragging text must not refocus the field.
- Tap another word to resume: cursor appears where tapped and Gboard opens.
  Press Back to read, then Back again to return to the correct list. Repeat with
  the keyboard already hidden, gesture navigation and three-button navigation,
  and a physical keyboard. A canceled Back gesture must leave editing intact.
- Select a word/range and press Back. Return to reading without stuck handles.
  Reenter editing and verify native selection, copy/paste, and caret scrolling.
- Force a save failure, then Back to reading: draft remains visible with Retry.
  Another Back cannot exit until saving succeeds. Restore access and retry.
- Recheck five-line tail spacing and transparent gesture bar on long notes.

Native framework tests cover focus/cursor clearing, retained document/scroll,
reading drags, and tapping to resume. Actual IME/system Back needs device checks.


## To-do flags (1.4.5)

- In each of the ten themes, create tasks with all seven nonempty flag combinations and one unflagged task. Check that all colors differ and text remains readable; emoji order is always 🚨 ❗ 🎯.
- Long-press and the three-dot menu must offer the same three toggles, with checks for selected options. Toggle a flag twice to remove it without changing timestamps or other flags.
- Edit flags using dialog checkboxes, cancel flag-only edits and confirm discard, then reopen to verify the saved selection. Creation has checkboxes and no “Save and add another.”
- Complete and reopen each combination. Task colors and emoji must stay unchanged. The Flagged filter includes emoji flags and legacy black flags.
- Open a format-1 fixture with both legacy flags and completed tasks. Edit text without changing its black flag; choose an emoji to replace it, or use Remove legacy flag. Reload after saving and verify IDs, descriptions, and timestamps.
- Rotate while the add/edit dialog is open; flags and draft text must survive. Failed saves retain selected flags for retry.

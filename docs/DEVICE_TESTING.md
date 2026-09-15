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
   The Android status/navigation areas remain protected from text overlap.
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

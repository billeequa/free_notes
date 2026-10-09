# Performance in 1.4.6

The interface, editor, gestures, note/task serializers, filenames and export layout are unchanged.

## Startup and index

The private `files/note-index/` directory contains a versioned JSON metadata index scoped to the selected folder and system time zone. It stores titles, dates, category, Double X metadata, document identity, provider size/stamp and validation time. It stores no note bodies and adds no files to the selected folder or exports.

After settings load, a valid index supplies Notes and Journal immediately. A background reconciliation queries root metadata in one batch and reads only changed/new/expired note headers. Unknown provider stamps are rechecked; positive unchanged stamps are trusted for at most 24 hours. Opening a note always reads its actual file. Missing, corrupt, incompatible or differently scoped indexes rebuild from the source files. Scan failures retain existing entries.

Trash is loaded when opened. Tasks start loading independently, including during the first index build. DataStore flows suppress unchanged settings so theme/font/flag preference changes do not trigger unrelated reloads. Resume reconciliation preserves existing lists rather than replacing them with a spinner.

## Saves and memory

A verified save updates the affected metadata entry and publishes a body-free snapshot. It does not rescan active notes or Trash. Normal body edits resolve the current URI and avoid directory enumeration. Renames enumerate names once for collision handling. Local pending-write recovery and complete provider read-back verification remain intact; two redundant full reads were removed.

Atomic index-write failures invalidate the cache without invalidating a successfully verified note save. Readers load full text only for the active editor. Header parsing no longer splits and rejoins the entire document body. Note lists and cached snapshots contain no full bodies.

Task sorting/filtering and measured open-item heights are cached by their inputs. Existing ordering and spacing are preserved. EPUB summaries survive tab disposal; folder preferences and explicit refresh/resume still reconcile the library. Full cover decoding moved off the UI thread and is sampled to the same 60-by-90-dp display size, with a four-MiB thumbnail cache. Readium construction is deferred until needed.

## Verification

Provider-backed regression tests assert zero provider calls for cached startup, no unchanged-note reads or Trash visits during warm reconciliation, one read-back for an ordinary save, unchanged file serialization, external additions/deletions/edits, pending recovery, and preservation of cached data on provider errors. Index tests cover body omission, folder isolation, invalidation, unknown/expired stamps, Double X identity changes, and failed cache writes. Existing editor, file-format, flag and gesture-support tests also run.

These checks establish eliminated work; actual wall-clock startup and navigation times still require measurement on the user's device. The first launch must construct the index, but normal notes need only header reads during that construction.

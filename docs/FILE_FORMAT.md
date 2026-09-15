# Note file format

Jnotes stores each note as UTF-8 plain text with `\n` line endings.

## Current format

```text
Title: Grocery list
Created: 2026-04-05 09:15:00
Modified: 2026-04-05 10:02:11

Milk
Eggs
Coffee
```

The first blank line separates metadata from the body. Everything after that separator is note content and is preserved verbatim when parsed.

Current timestamps are local device time to the second:

```text
yyyy-MM-dd HH:mm:ss
```

This format is human-readable, lexically sortable, and independent of locale-specific date conventions. It intentionally does not store a time-zone offset.

## Backward compatibility

The parser also accepts the previous ISO-8601 offset format:

```text
Created: 2026-03-24T09:15:00-04:00
Modified: 2026-03-24T10:02:11-04:00
```

When a legacy note is next saved, it is serialized using the current local-time format.

## Recovery rules

If metadata is missing or malformed, Jnotes favors retaining content:

- No recognized headers: treat the entire file as the body.
- Missing title: use the first non-empty body line, then the filename.
- Missing creation time: use modified time, file metadata, or current time.
- Missing modified time: use file metadata or current time.
- Unknown text before the first blank line: treat the file as body text instead of discarding it.

## Filenames

The preferred filename is:

```text
yyyyMMdd-HHmmss-title-slug.txt
```

The timestamp is the note's creation time and does not change when the title changes. Titles are lowercased, non-alphanumeric runs become hyphens, and the slug is limited to 40 characters. Empty titles use `untitled`. Collisions receive numeric suffixes such as `-2` and `-3`.



## To-do timeline

`to_do_jnotes.txt` is reserved for the To-do tab, excluded from ordinary notes,
and included in ZIP exports. It is a UTF-8 text file with this versioned format:

```text
Jnotes To-do: 1

id: 907b24eb-a5d0-4721-b2be-47c0bdeeb836
title: Check cavity calibration
description: Optional details
added: 2026-09-15T09:00:00-04:00
completed: 2026-09-15T10:00:00-04:00
flagged: true
```

Every record has these six fields in order. Incomplete items have an empty
`completed: ` value. IDs are stable UUIDs. Dates use ISO-8601 offsets; the UI
shows local time. Titles and descriptions escape backslashes as `\\`, newlines
as `\n`, and carriage returns as `\r`. Unknown versions, incomplete records,
and duplicate IDs are rejected rather than silently overwritten.

The display sorts by creation time, oldest first, and opens at the newest
unfinished item (or the last item if everything is complete). Completion keeps
an item in place. Reopening clears its completion timestamp. Editing and flagging
preserve creation/completion timestamps. Permanent deletion requires confirmation.

Writes keep a private on-device recovery copy until provider read-back verifies
the UTF-8 contents. Opening a file with a pending write retries that write before
parsing. Renaming a note writes and verifies a replacement before deleting the
previous file. Storage-provider atomic replacement is not universally available;
interruption during a rename can leave a duplicate, rather than discard the old copy.

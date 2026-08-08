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

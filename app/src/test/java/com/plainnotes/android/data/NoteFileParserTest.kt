package com.plainnotes.android.data

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFileParserTest {
    @Test
    fun `parse reads local-time header format`() {
        val raw = """
            Title: Grocery list
            Created: 2026-03-28 15:30:12
            Modified: 2026-03-28 15:44:10

            Milk
            Eggs
            Coffee
        """.trimIndent()

        val parsed = NoteFileParser.parse(
            rawText = raw,
            fallbackFileName = "ignored.txt",
            fallbackLastModifiedMillis = 0L,
            now = OffsetDateTime.parse("2026-03-28T16:00:00-04:00"),
        )

        assertEquals("Grocery list", parsed.title)
        assertEquals("2026-03-28T15:30:12", parsed.createdAt.toLocalDateTime().toString())
        assertEquals("2026-03-28T15:44:10", parsed.modifiedAt.toLocalDateTime().toString())
        assertEquals("Milk\nEggs\nCoffee", parsed.body)
    }

    @Test
    fun `parse keeps blank explicit title instead of inferring from body`() {
        val raw = """
            Title:
            Created: 2026-03-28 15:30:12
            Modified: 2026-03-28 15:44:10

            First body line
        """.trimIndent()

        val parsed = NoteFileParser.parse(
            rawText = raw,
            fallbackFileName = "blank-title.txt",
            fallbackLastModifiedMillis = 0L,
            now = OffsetDateTime.parse("2026-03-28T16:00:00-04:00"),
        )

        assertEquals("", parsed.title)
        assertEquals("First body line", parsed.body)
    }

    @Test
    fun `parse remains backward compatible with legacy offset timestamps`() {
        val raw = """
            Title: Legacy note
            Created: 2026-03-28T15:30:12-04:00
            Modified: 2026-03-28T15:44:10-04:00

            Old body
        """.trimIndent()

        val parsed = NoteFileParser.parse(
            rawText = raw,
            fallbackFileName = "legacy-note.txt",
            fallbackLastModifiedMillis = 0L,
            now = OffsetDateTime.parse("2026-03-28T16:00:00-04:00"),
        )

        assertEquals("Legacy note", parsed.title)
        assertEquals("2026-03-28T15:30:12-04:00", parsed.createdAt.toString())
        assertEquals("2026-03-28T15:44:10-04:00", parsed.modifiedAt.toString())
        assertEquals("Old body", parsed.body)
    }

    @Test
    fun `parse falls back for malformed files without dropping text`() {
        val raw = """
            Some hand-written file
            with no expected header at all
            https://example.com
        """.trimIndent()

        val parsed = NoteFileParser.parse(
            rawText = raw,
            fallbackFileName = "manual-import.txt",
            fallbackLastModifiedMillis = 0L,
            now = OffsetDateTime.parse("2026-03-28T16:00:00-04:00"),
        )

        assertEquals("Some hand-written file", parsed.title)
        assertTrue(parsed.body.contains("https://example.com"))
    }
}

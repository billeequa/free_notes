package com.plainnotes.android.data

import com.plainnotes.android.model.NoteCategory
import com.plainnotes.android.model.NoteTextContent
import com.plainnotes.android.model.NoteType
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalMetadataTest {
    private val now = OffsetDateTime.parse("2026-10-04T15:00:00-04:00")
    private val date = LocalDate.parse("2026-10-04")
    private val body = "\nPersonal text\n\nCategory: this is part of the body.\n"

    private fun roundTrip(content: NoteTextContent) =
        NoteFileParser.parse(NoteFileParser.serialize(content), "note.txt", 0L, now)

    @Test fun `journal metadata survives saves and preserves the entire body`() {
        val content = NoteTextContent("2026-10-04 II", now, now, body,
            category = NoteCategory.JOURNAL, journalDate = date)
        val parsed = roundTrip(content)
        assertEquals(NoteCategory.JOURNAL, parsed.category)
        assertEquals(NoteType.NORMAL, parsed.noteType)
        assertEquals(date, parsed.journalDate)
        assertEquals(content.title, parsed.title)
        assertEquals(body, parsed.body)
    }

    @Test fun `moving either way preserves Double X flag date and text`() {
        val original = NoteTextContent("2026-10-04 Double X Day", now, now, body, NoteType.DOUBLE_X_DAY, date)
        for (category in listOf(NoteCategory.NOTES, NoteCategory.JOURNAL)) {
            val parsed = roundTrip(original.copy(category = category))
            assertEquals(category, parsed.category)
            assertEquals(NoteType.DOUBLE_X_DAY, parsed.noteType)
            assertEquals(date, parsed.doubleXDate)
            assertEquals(body, parsed.body)
        }
    }

    @Test fun `legacy Double X files migrate to journals with the standard title`() {
        val legacy = "Title: Double X Day — October 4, 2026\nCreated: 2026-10-04 15:00:00\nModified: 2026-10-04 15:00:00\nNote-Type: DOUBLE_X_DAY\nDouble-X-Date: 2026-10-04\n\n$body"
        val parsed = NoteFileParser.parse(legacy, "double-x-day.txt", 0L, now)
        assertEquals(NoteCategory.JOURNAL, parsed.category)
        assertEquals("2026-10-04 Double X Day", parsed.title)
        assertEquals(date, parsed.journalDate)
        assertEquals(body, parsed.body)
    }

    @Test fun `ordinary date titled notes stay in Notes until manually moved`() {
        val ordinary = NoteFileParser.parse("Title: 2026-10-04\nCreated: 2026-10-04 15:00:00\nModified: 2026-10-04 15:00:00\n\n$body", "daily.txt", 0L, now)
        assertEquals(NoteCategory.NOTES, ordinary.category)
        assertNull(ordinary.journalDate)
        assertEquals(body, ordinary.body)
    }

    @Test fun `a dated journal stays a journal without Double X metadata`() {
        val parsed = NoteFileParser.parse("Title: My journal\nCategory: JOURNAL\n\n$body", "daily.txt", 0L, now)
        assertEquals(NoteCategory.JOURNAL, parsed.category)
        assertEquals(date, parsed.journalDate)
        assertEquals(body, parsed.body)
    }
}

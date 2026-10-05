package com.plainnotes.android.data

import com.plainnotes.android.model.NoteTextContent
import com.plainnotes.android.model.NoteType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DoubleXTemplateTest {
    private val now = OffsetDateTime.parse("2026-10-04T22:00:00-04:00")
    private val date = LocalDate.parse("2026-10-04")
    private val zone = ZoneId.of("America/New_York")
    private fun note(body: String = "") = NoteTextContent("2026-10-04 Double X Day", now, now,
        body, NoteType.DOUBLE_X_DAY, date)
    private fun task(title: String, completed: String?) = TodoItem(title = title, addedAt = now,
        completedAt = completed?.let(OffsetDateTime::parse))
    private fun reopen(content: NoteTextContent) = NoteFileParser.parse(
        NoteFileParser.serialize(content), "journal.txt", 0L, now)

    @Test fun templateUsesSameDayCompletionsInCompletionOrderAndPreservesWriting() {
        val writing = "\nExisting writing\n\nLast paragraph  \n"
        val content = DoubleXDay.withTemplate(note(writing), listOf(
            task("Second", "2026-10-05T01:00:00Z"),
            task("First", "2026-10-04T16:00:00Z"),
            task("Other day", "2026-10-05T05:00:00Z"), task("Open", null),
        ), zone)
        assertEquals("Things Accomplished\n✓ First\n✓ Second\n\nNotes on the Day\n\n$writing", content.body)
        assertEquals(content.body, reopen(content).body)
        assertEquals(1, reopen(content).doubleXTemplateVersion)
    }

    @Test fun editedOrDeletedTemplateIsNeverRecreatedOnReopen() {
        val inserted = DoubleXDay.withTemplate(note(), emptyList(), zone)
        assertTrue(inserted.body.contains("No completed tasks yet."))
        for (body in listOf("My edited headings\n✓ Custom accomplishment\nMy writing", "")) {
            val edited = reopen(inserted.copy(body = body, title = "My editable title"))
            val reopened = DoubleXDay.withTemplate(edited, listOf(task("Later task", now.toString())), zone)
            assertEquals(body, reopened.body)
            assertEquals("My editable title", reopened.title)
        }
    }

    @Test fun legacyDoubleXGetsOneTemplateAndOrdinaryNotesRemainUntouched() {
        val legacy = reopen(note("Previous entry"))
        val migrated = DoubleXDay.withTemplate(legacy, emptyList(), zone)
        val reopened = reopen(migrated)
        assertSame(reopened, DoubleXDay.withTemplate(reopened, emptyList(), zone))
        assertEquals(migrated.body, reopened.body)
        val ordinary = note("Ordinary text").copy(noteType = NoteType.NORMAL, doubleXDate = null)
        assertSame(ordinary, DoubleXDay.withTemplate(ordinary, emptyList(), zone))
    }
}

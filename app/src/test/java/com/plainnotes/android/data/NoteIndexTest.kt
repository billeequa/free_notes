package com.plainnotes.android.data

import android.net.Uri
import com.plainnotes.android.model.*
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private val time = OffsetDateTime.parse("2026-10-08T12:00:00-04:00")
    private fun note(uri: String = "content://notes/document/one", type: NoteType = NoteType.NORMAL) =
        EditableNote(Uri.parse(uri), "one.txt", "Title 🚨\nUnicode", "PRIVATE BODY MUST NOT BE INDEXED",
            time, time, false, type, if (type == NoteType.DOUBLE_X_DAY) LocalDate.parse("2026-10-08") else null)

    @Test fun indexRoundTripsMetadataWithoutBodiesAndIsFolderScoped() {
        val folder = temp.newFolder()
        val original = note(type = NoteType.DOUBLE_X_DAY)
        val index = NoteIndex(folder, "folder-a", "My notes")
        index.put(IndexedNote(original.toDocument(), 123L, 9000L, 456L))
        index.persist()
        val json = folder.listFiles()!!.single().readText()
        assertFalse(json.contains(original.body))
        val restored = NoteIndex(folder, "folder-a", "Fallback")
        assertTrue(restored.load())
        assertEquals(index.snapshot(), restored.snapshot())
        assertEquals(original.documentUri.toString(), restored.doubleXDates[original.doubleXDate])
        assertFalse(NoteIndex(folder, "folder-b", "Other").load())
        assertFalse(NoteDocument::class.java.declaredFields.any { it.name == "body" })
    }

    @Test fun corruptOrFutureIndexIsDiscardedWithoutLosingTheSourceFile() {
        val folder = temp.newFolder()
        val source = temp.newFile("source.txt").apply { writeText("User text stays untouched") }
        val index = NoteIndex(folder, "root", "Notes")
        index.put(IndexedNote(note().toDocument(), null, null, 10L)); index.persist()
        val file = folder.listFiles()!!.single()
        file.writeText("broken JSON")
        assertFalse(NoteIndex(folder, "root", "Notes").load())
        index.persist()
        file.writeText(file.readText().replace("\"version\":1", "\"version\":99"))
        assertFalse(NoteIndex(folder, "root", "Notes").load())
        assertEquals("User text stays untouched", source.readText())
    }

    @Test fun changedRenamedUnknownOrExpiredEntriesAreRechecked() {
        val summary = note().toDocument()
        val record = IndexedNote(summary, 100L, 200L, 1000L)
        val entry = DocumentEntry(summary.documentUri, summary.filename, "text/plain", 100L, 200L)
        assertTrue(record.matches(entry, 2000L))
        assertFalse(record.matches(entry.copy(size = 201L), 2000L))
        assertFalse(record.matches(entry.copy(modifiedMillis = 101L), 2000L))
        assertFalse(record.matches(entry.copy(name = "renamed.txt"), 2000L))
        assertFalse(record.matches(entry.copy(modifiedMillis = null), 2000L))
        assertFalse(record.matches(entry.copy(modifiedMillis = 0L), 2000L))
        assertFalse(record.matches(entry, 1000L + 24 * 60 * 60 * 1000L))
        assertFalse(record.matches(entry, 999L))
    }

    @Test fun renameAndRemovalMaintainDoubleXLookup() {
        val index = NoteIndex(temp.newFolder(), "root", "Notes")
        val original = note(type = NoteType.DOUBLE_X_DAY).toDocument()
        index.put(IndexedNote(original, null, null, 0L))
        index.remove(original.id)
        val renamed = original.copy(documentUri = Uri.parse("content://notes/document/two"), filename = "two.txt")
        index.put(IndexedNote(renamed, null, null, 0L))
        assertEquals(listOf(renamed), index.snapshot().notes)
        assertEquals(renamed.id, index.doubleXDates[renamed.doubleXDate])
        index.put(IndexedNote(renamed.copy(noteType = NoteType.NORMAL, doubleXDate = null), null, null, 0L))
        assertTrue(index.doubleXDates.isEmpty())
    }

    @Test fun cacheWriteFailureDoesNotPreventACompletedNoteSave() {
        val index = NoteIndex(temp.newFile("cannot-be-a-directory"), "root", "Notes")
        index.put(IndexedNote(note().toDocument(), null, null, 0L))
        index.persist()
        assertEquals(1, index.snapshot().notes.size)
    }
}

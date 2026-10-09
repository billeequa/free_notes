package com.plainnotes.android.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.plainnotes.android.model.NoteTextContent
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesRepositoryIndexTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: CountingNotesProvider
    private val tree = DocumentsContract.buildTreeDocumentUri("index-test", "root")
    private val now = OffsetDateTime.parse("2026-10-08T12:00:00Z")

    @Before fun setup() = runBlocking {
        File(context.filesDir, "note-index").deleteRecursively()
        File(context.filesDir, "pending-writes").deleteRecursively()
        provider = CountingNotesProvider(File(context.cacheDir, "provider").apply { deleteRecursively(); mkdirs() })
        provider.attachInfo(context, ProviderInfo().apply { authority = "index-test"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("index-test", provider)
        AppSettingsRepository(context).setRootFolderUri(tree)
    }

    private fun add(name: String, title: String = name, body: String = "Long body\n".repeat(10000)): File =
        File(provider.root, name).apply { writeText(NoteFileParser.serialize(NoteTextContent(title, now, now, body))) }

    @Test fun startupUsesPrivateIndexAndWarmRefreshDoesNotOpenUnchangedNotesOrTrash() = runBlocking {
        val first = add("first.txt")
        add("second.txt")
        File(provider.root, "Trash").mkdirs()
        File(provider.root, "Trash/deleted.txt").writeText("Deleted body")
        val repository = NotesRepository(context)
        assertNull(repository.loadCachedNotes(tree))
        assertEquals(2, repository.listActiveNotes().size)
        assertEquals(2, provider.reads)
        provider.reset()
        val restarted = NotesRepository(context)
        val cached = restarted.loadCachedNotes(tree)
        assertEquals(2, cached!!.notes.size)
        assertEquals(0, provider.queries)
        assertEquals(0, provider.reads)
        restarted.listActiveNotes()
        assertEquals(0, provider.reads)
        assertFalse(provider.visitedTrash)
        // A changed file is the only body/header reopened on reconciliation.
        first.appendText("External edit")
        restarted.listActiveNotes()
        assertEquals(1, provider.reads)
    }

    @Test fun saveUpdatesOneIndexEntryWithOneVerificationReadAndUnchangedFormat() = runBlocking {
        val body = "User text\n\nLast line\n"
        val file = add("20261008-120000-first.txt", "first", body)
        add("unrelated.txt")
        val repository = NotesRepository(context)
        repository.listActiveNotes()
        val note = repository.loadEditableNote(DocumentsContract.buildDocumentUriUsingTree(tree, "root/${file.name}").toString())!!
        provider.reset()
        val saved = repository.saveNote(note.copy(body = "$body Edited"))
        assertEquals(1, provider.reads)
        assertEquals(1, provider.writes)
        assertEquals(0, provider.childQueries)
        assertEquals(2, repository.loadCachedNotes(tree)!!.notes.size)
        val parsed = NoteFileParser.parse(file.readText(), file.name, file.lastModified())
        assertEquals(saved.body, parsed.body)
        assertEquals(saved.title, parsed.title)
        assertEquals(NoteFileParser.serialize(parsed), file.readText())
        assertFalse(File(context.filesDir, "pending-writes").listFiles().orEmpty().any { it.extension == "txt" })
    }

    @Test fun deletionAdditionAndHeaderlessTitlesReconcileWithoutTouchingFiles() = runBlocking {
        val old = add("old.txt")
        val repository = NotesRepository(context)
        repository.listActiveNotes()
        old.delete()
        File(provider.root, "manual.txt").writeText("\n\nMy handwritten title\nBody\n")
        val notes = repository.listActiveNotes()
        assertEquals(1, notes.size)
        assertEquals("My handwritten title", notes.single().title)
        assertEquals("\n\nMy handwritten title\nBody\n", File(provider.root, "manual.txt").readText())
    }

    @Test fun pendingRecoveryOverridesAnUnchangedProviderStamp() = runBlocking {
        val file = add("recover.txt", "Before", "Old body")
        val repository = NotesRepository(context)
        repository.listActiveNotes()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "root/${file.name}")
        val key = java.util.UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString()
        val pending = File(context.filesDir, "pending-writes/$key.txt").apply { parentFile!!.mkdirs() }
        pending.writeText(NoteFileParser.serialize(NoteTextContent("Recovered", now, now, "Recovered text")))
        assertEquals("Recovered", repository.listActiveNotes().single().title)
        assertFalse(pending.exists())
        assertTrue(file.readText().contains("Recovered text"))
    }

    @Test fun failedDirectoryQueryRetainsCachedNotes() = runBlocking {
        add("first.txt")
        val repository = NotesRepository(context)
        repository.listActiveNotes()
        provider.failQueries = true
        try { repository.listActiveNotes(); fail("A failed scan must not become an empty collection") }
        catch (_: java.io.IOException) { }
        assertEquals(1, repository.loadCachedNotes(tree)!!.notes.size)
    }
}

/** Real streams and provider metadata let tests count storage work instead of guessing speed. */
private class CountingNotesProvider(val root: File) : ContentProvider() {
    var reads = 0; var writes = 0; var queries = 0; var childQueries = 0
    var visitedTrash = false; var failQueries = false
    fun reset() { reads = 0; writes = 0; queries = 0; childQueries = 0; visitedTrash = false }
    override fun onCreate() = true
    private fun file(id: String) = if (id == "root") root else File(root, id.removePrefix("root/"))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        queries++
        if (failQueries) throw java.io.IOException("Provider unavailable")
        val id = DocumentsContract.getDocumentId(uri)
        if (id.contains("Trash")) visitedTrash = true
        val folder = file(id)
        val children = uri.lastPathSegment == "children"
        if (children) childQueries++
        val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type", "last_modified", "_size")
        return MatrixCursor(columns).apply {
            val files = if (children) folder.listFiles().orEmpty().toList() else if (folder.exists()) listOf(folder) else emptyList()
            files.forEach { item ->
                addRow(columns.map { column -> when (column) {
                    "document_id" -> if (item == root) "root" else "root/${item.relativeTo(root).invariantSeparatorsPath}"
                    "_display_name" -> if (item == root) "My notes" else item.name
                    "mime_type" -> if (item.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
                    "last_modified" -> item.lastModified()
                    "_size" -> item.length()
                    else -> 0
                } }.toTypedArray())
            }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains('w')) writes++ else reads++
        return ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))
    }
    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

package com.plainnotes.android.reader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import org.json.JSONObject
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
class BookEditsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val tree = DocumentsContract.buildTreeDocumentUri("epub-test", "root")
    private val cache get() = context.getSharedPreferences("book-edit-test", Context.MODE_PRIVATE)
    private lateinit var provider: EpubDocumentsProvider
    private lateinit var file: File
    private lateinit var book: BookEntry
    private fun key(uri: Uri) = uri.toString()
    private fun edits() = BookEdits(context, cache, ::key)

    @Before fun setup() {
        cache.edit().clear().commit()
        File(context.filesDir, "book-edits").deleteRecursively()
        val root = File(context.cacheDir, "epub-provider").apply { deleteRecursively(); mkdirs() }
        provider = EpubDocumentsProvider(root)
        provider.attachInfo(context, ProviderInfo().apply { authority = "epub-test"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("epub-test", provider)
        file = sampleEpub(File(root, "nested/original.epub"))
        val uri = provider.uri(file)
        book = BookEntry(uri, tree, "Original", "Author", null, .4, 123L, file.name,
            provider.uri(file.parentFile!!), "${file.lastModified()}:${file.length()}")
        cache.edit().putString("meta:${key(uri)}", JSONObject().put("title", book.title).put("author", book.author).put("cover", "cover.png").toString())
            .putString("locator:${key(uri)}", "position-json").putFloat("progress:${key(uri)}", .4f).putLong("last:${key(uri)}", 123L).commit()
    }

    @Test fun renameAndMetadataEditRetainReadingPositionAcrossChangedUri() {
        val uri = edits().save(book, "renamed", "New title", "New author")
        assertNotEquals(book.uri, uri)
        assertFalse(file.exists())
        assertEquals(EpubBookMetadata("New title", "New author"), EpubMetadata.read(File(file.parentFile, "renamed.epub")))
        assertEquals("position-json", cache.getString("locator:${key(uri)}", null))
        assertEquals(.4f, cache.getFloat("progress:${key(uri)}", 0f), .0001f)
        assertEquals(123L, cache.getLong("last:${key(uri)}", 0))
        assertFalse(cache.contains("locator:${key(book.uri)}"))
        assertTrue(File(context.filesDir, "book-edits").listFiles().orEmpty().isEmpty())
    }

    @Test fun filenameOnlyRenamePreservesEveryByte() {
        val bytes = file.readBytes(); edits().save(book, "renamed", book.title, book.author)
        assertArrayEquals(bytes, File(file.parentFile, "renamed.epub").readBytes())
    }

    @Test fun duplicateNameAndStaleSourceAreRejectedWithoutTouchingBooks() {
        val bytes = file.readBytes(); File(file.parentFile, "other.epub").writeText("Unrelated")
        assertThrows(IllegalArgumentException::class.java) { edits().save(book, "other", "Changed", "Author") }
        assertArrayEquals(bytes, file.readBytes())
        file.appendText("changed externally")
        assertThrows(IllegalArgumentException::class.java) { edits().save(book, "new", "Changed", "Author") }
        assertTrue(file.readText().endsWith("changed externally"))
    }

    @Test fun partialProviderWriteRestoresOriginalAndReadingState() {
        val original = file.readBytes(); provider.failWriteOnce = true
        assertThrows(Exception::class.java) { edits().save(book, "renamed", "Changed", "Author") }
        assertArrayEquals(original, file.readBytes())
        assertEquals("position-json", cache.getString("locator:${key(book.uri)}", null))
        assertFalse(File(file.parentFile, "renamed.epub").exists())
        assertTrue(File(context.filesDir, "book-edits").listFiles().orEmpty().isEmpty())
    }

    @Test fun failedRenameRestoresOriginalMetadata() {
        val original = file.readBytes(); provider.failRenameOnce = true
        assertThrows(Exception::class.java) { edits().save(book, "renamed", "Changed", "Author") }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun processDeathAfterRenameRecoversOriginalFromDurableJournal() {
        val original = file.readBytes(); provider.crashAfterRename = true
        assertThrows(SimulatedProcessDeath::class.java) { edits().save(book, "renamed", "Changed", "Author") }
        assertFalse(file.exists())
        assertTrue(File(context.filesDir, "book-edits").listFiles().orEmpty().any { it.extension == "json" })
        provider.crashAfterRename = false
        edits().recover()
        assertArrayEquals(original, file.readBytes())
        assertFalse(File(file.parentFile, "renamed.epub").exists())
        assertEquals("position-json", cache.getString("locator:${key(book.uri)}", null))
    }
}

private class SimulatedProcessDeath : Error()
private class EpubDocumentsProvider(val root: File) : ContentProvider() {
    var failWriteOnce = false
    var failRenameOnce = false
    var crashAfterRename = false
    private val tree = DocumentsContract.buildTreeDocumentUri("epub-test", "root")
    private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).invariantSeparatorsPath
    private fun file(uri: Uri) = DocumentsContract.getDocumentId(uri).let { if (it == "root") root else File(root, it.removePrefix("root/")) }
    fun uri(file: File) = DocumentsContract.buildDocumentUriUsingTree(tree, id(file))
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val files = if (uri.lastPathSegment == "children") file.listFiles().orEmpty().toList() else listOf(file).filter { it.exists() }
        val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type", "last_modified", "_size", "flags")
        return MatrixCursor(columns).apply { files.forEach { f -> addRow(columns.map { column -> when (column) {
            "document_id" -> id(f); "_display_name" -> f.name
            "mime_type" -> if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/epub+zip"
            "last_modified" -> f.lastModified(); "_size" -> f.length(); "flags" -> 0; else -> null
        } }.toTypedArray<Any?>()) } }
    }
    override fun getType(uri: Uri) = if (file(uri).isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/epub+zip"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains('w') && failWriteOnce) {
            failWriteOnce = false; file(uri).writeText("partial write"); throw IOException("Injected provider write failure")
        }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(method == "android:renameDocument")
        if (failRenameOnce) { failRenameOnce = false; throw IOException("Injected rename failure") }
        val source = file(extras!!.getParcelable<Uri>("uri")!!)
        val target = File(source.parentFile, extras.getString("_display_name")!!)
        check(source.renameTo(target))
        if (crashAfterRename) throw SimulatedProcessDeath()
        return Bundle().apply { putParcelable("uri", uri(target)) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

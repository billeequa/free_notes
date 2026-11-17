package com.plainnotes.android.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.plainnotes.android.data.AppSettingsRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

data class BookEntry(
    val uri: Uri,
    val folderUri: Uri,
    val title: String,
    val author: String,
    val coverPath: String?,
    val progress: Double,
    val lastRead: Long,
    val filename: String,
    val parentUri: Uri,
    val sourceStamp: String,
) {
    val displayTitle: String get() = title.ifBlank { if (author.isNotBlank()) "Untitled" else filename }
}

/** Readium and the SAF live behind this boundary; visibility is stored independently. */
class BookLibrary(private val context: Context) {
    private val settings = AppSettingsRepository(context)
    private val cache = context.getSharedPreferences("book_library", Context.MODE_PRIVATE)
    private val edits by lazy { BookEdits(context, cache, ::key) }
    private val documents = com.plainnotes.android.data.DocumentDirectory(context.contentResolver)
    private val http by lazy { DefaultHttpClient() }
    private val retriever by lazy { AssetRetriever(context.contentResolver, http) }
    private val opener by lazy { PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null)) }

    suspend fun folders(): List<Uri> = settings.bookFolders.first()

    suspend fun addFolder(uri: Uri) {
        val resolver = context.contentResolver
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        settings.addBookFolder(uri)
    }

    suspend fun removeFolder(uri: Uri) {
        settings.removeBookFolder(uri)
        // Reader progression and metadata are retained if the folder is added again.
        if (settings.rootFolderUri.first() != uri) {
            context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let { grant ->
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
            }
        }
    }

    suspend fun scan(): List<BookEntry> = withContext(Dispatchers.IO) {
        bookMutex.withLock {
            edits.recover()
            folders().flatMap { folder ->
                val root = DocumentFile.fromTreeUri(context, folder) ?: return@flatMap emptyList()
                epubFiles(root).map { (file, parentUri) ->
                    val key = key(file.uri)
                    val stamp = "${file.modifiedMillis}:${file.size}"
                    var metadata = runCatching { JSONObject(cache.getString("meta:$key", "{}")!!) }.getOrDefault(JSONObject())
                    if (metadata.optString("stamp") != stamp || metadata.optInt("metadata_version") != 2) {
                        var title = ""; var author = ""; var cover: String? = null
                        try {
                            val local = copyBook(file.uri)
                            val fields = EpubMetadata.read(local)
                            title = fields.title; author = fields.author
                            val publication = openPublication(file.uri, local)
                            try {
                                val coverFile = File(context.filesDir, "book-cover-$key.png")
                                publication.cover()?.let { bitmap ->
                                    coverFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, it) }
                                    cover = coverFile.path
                                }
                            } finally { publication.close() }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* Keep filename fallback available for unreadable EPUBs. */ }
                        metadata = JSONObject().put("title", title).put("author", author)
                            .put("cover", cover.orEmpty()).put("stamp", stamp).put("metadata_version", 2)
                        cache.edit().putString("meta:$key", metadata.toString()).apply()
                    }
                    BookEntry(file.uri, folder, metadata.optString("title"), metadata.optString("author"),
                        metadata.optString("cover").takeIf { it.isNotBlank() },
                        cache.getFloat("progress:$key", 0f).toDouble(), cache.getLong("last:$key", 0L),
                        file.name, parentUri, stamp)
                }
            }.distinctBy { it.uri.toString() }.sortedWith(compareByDescending<BookEntry> { it.lastRead }.thenBy { it.displayTitle })
        }
    }

    suspend fun saveDetails(book: BookEntry, stem: String, title: String, author: String): Uri = withContext(Dispatchers.IO) {
        bookMutex.withLock { edits.save(book, stem, title, author) }
    }

    private fun epubFiles(root: DocumentFile): List<Pair<com.plainnotes.android.data.DocumentEntry, Uri>> {
        val seen = mutableSetOf<String>()
        val files = mutableListOf<Pair<com.plainnotes.android.data.DocumentEntry, Uri>>()
        fun visit(folder: DocumentFile) {
            if (!seen.add(folder.uri.toString())) return
            documents.children(folder).forEach { entry ->
                when {
                    entry.isDirectory -> folder.listFiles().firstOrNull { it.uri == entry.uri }?.let(::visit)
                    entry.name.endsWith(".epub", ignoreCase = true) -> files.add(entry to folder.uri)
                }
            }
        }
        visit(root)
        return files
    }

    suspend fun open(uri: Uri): Publication = withContext(Dispatchers.IO) {
        bookMutex.withLock { edits.recover(); openPublication(uri) }
    }

    private fun copyBook(uri: Uri): File {
        val file = File(context.cacheDir, "epub-${key(uri)}.epub")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("This EPUB is no longer accessible. Check its books folder.")
        return file
    }

    private suspend fun openPublication(uri: Uri, file: File = copyBook(uri)): Publication {
        val asset = retriever.retrieve(file).getOrElse { error("Cannot read this EPUB.") }
        val publication = opener.open(asset, allowUserInteraction = false).getOrElse { error("Unsupported or damaged EPUB.") }
        if (publication.isRestricted || !publication.conformsTo(Publication.Profile.EPUB)) {
            publication.close()
            error("This EPUB is protected or is not a supported EPUB.")
        }
        return publication
    }

    fun locator(uri: Uri): String? = cache.getString("locator:${key(uri)}", null)
    fun savePosition(uri: Uri, locatorJson: String, progress: Double) {
        cache.edit().putString("locator:${key(uri)}", locatorJson)
            .putFloat("progress:${key(uri)}", progress.toFloat())
            .putLong("last:${key(uri)}", System.currentTimeMillis()).apply()
    }

    private companion object { val bookMutex = Mutex() }

    private fun key(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
}

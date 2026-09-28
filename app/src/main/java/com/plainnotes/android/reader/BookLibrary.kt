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
)

/** Readium and the SAF live behind this boundary; visibility is stored independently. */
class BookLibrary(private val context: Context) {
    private val settings = AppSettingsRepository(context)
    private val cache = context.getSharedPreferences("book_library", Context.MODE_PRIVATE)
    private val http = DefaultHttpClient()
    private val retriever = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))

    suspend fun folders(): List<Uri> = settings.bookFolders.first()

    suspend fun addFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.addBookFolder(uri)
    }

    suspend fun removeFolder(uri: Uri) {
        settings.removeBookFolder(uri)
        // Reader progression and metadata are retained if the folder is added again.
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    suspend fun scan(): List<BookEntry> = withContext(Dispatchers.IO) {
        folders().flatMap { folder ->
            val root = DocumentFile.fromTreeUri(context, folder) ?: return@flatMap emptyList()
            epubFiles(root).map { file ->
                val key = key(file.uri)
                val stamp = "${file.lastModified()}:${file.length()}"
                var metadata = runCatching { JSONObject(cache.getString("meta:$key", "{}")!!) }.getOrDefault(JSONObject())
                if (metadata.optString("stamp") != stamp || !metadata.has("title")) {
                    metadata = try {
                        val publication = open(file.uri)
                        val coverFile = File(context.filesDir, "book-cover-$key.png")
                        publication.cover()?.let { bitmap ->
                            coverFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, it) }
                        }
                        JSONObject().put("title", publication.metadata.title ?: file.name?.removeSuffix(".epub"))
                            .put("author", publication.metadata.authors.joinToString(", ") { it.name })
                            .put("cover", if (coverFile.exists()) coverFile.path else "")
                            .put("stamp", stamp)
                    } catch (_: Exception) {
                        JSONObject().put("title", file.name?.removeSuffix(".epub") ?: "Unknown book")
                            .put("author", "").put("cover", "").put("stamp", stamp)
                    }
                    cache.edit().putString("meta:$key", metadata.toString()).apply()
                }
                BookEntry(file.uri, folder, metadata.optString("title"), metadata.optString("author"),
                    metadata.optString("cover").takeIf { it.isNotBlank() },
                    cache.getFloat("progress:$key", 0f).toDouble(), cache.getLong("last:$key", 0L))
            }
        }.distinctBy { it.uri.toString() }.sortedWith(compareByDescending<BookEntry> { it.lastRead }.thenBy { it.title })
    }

    private fun epubFiles(root: DocumentFile): List<DocumentFile> {
        val seen = mutableSetOf<String>()
        fun visit(folder: DocumentFile): List<DocumentFile> {
            if (!seen.add(folder.uri.toString())) return emptyList()
            return runCatching { folder.listFiles().toList() }.getOrDefault(emptyList()).flatMap { child ->
                when {
                    child.isDirectory -> visit(child)
                    child.isFile && child.name?.endsWith(".epub", ignoreCase = true) == true -> listOf(child)
                    else -> emptyList()
                }
            }
        }
        return visit(root)
    }

    suspend fun open(uri: Uri): Publication = withContext(Dispatchers.IO) {
        // Copy SAF input to a private cache file. ZIP parsers need seekable input; the
        // original EPUB remains in its user-owned folder and is never changed.
        val file = File(context.cacheDir, "epub-${key(uri)}.epub")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
            ?: error("This EPUB is no longer accessible. Check its books folder.")
        val asset = retriever.retrieve(file).getOrElse { error("Cannot read this EPUB.") }
        val publication = opener.open(asset, allowUserInteraction = false).getOrElse { error("Unsupported or damaged EPUB.") }
        if (publication.isRestricted) error("This EPUB is protected and cannot be opened.")
        if (!publication.conformsTo(Publication.Profile.EPUB)) error("This file is not a supported EPUB.")
        publication
    }

    fun locator(uri: Uri): String? = cache.getString("locator:${key(uri)}", null)
    fun savePosition(uri: Uri, locatorJson: String, progress: Double) {
        cache.edit().putString("locator:${key(uri)}", locatorJson)
            .putFloat("progress:${key(uri)}", progress.toFloat())
            .putLong("last:${key(uri)}", System.currentTimeMillis()).apply()
    }

    private fun key(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
}
